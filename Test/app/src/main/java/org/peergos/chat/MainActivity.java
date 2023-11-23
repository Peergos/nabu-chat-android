package org.peergos.chat;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import org.peergos.BlockRequestAuthoriser;
import org.peergos.EmbeddedIpfs;
import org.peergos.HostBuilder;
import org.peergos.blockstore.Blockstore;
import org.peergos.blockstore.RamBlockstore;
import org.peergos.config.Config;
import org.peergos.config.IdentitySection;
import org.peergos.net.ConnectionException;
import org.peergos.protocol.dht.RamRecordStore;
import org.peergos.protocol.dht.RecordStore;
import org.peergos.protocol.http.HttpProtocol;
import org.peergos.util.JSONParser;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import io.ipfs.multiaddr.MultiAddress;
import io.ipfs.multihash.Multihash;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.multiformats.Multiaddr;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import android.app.Activity;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.peergos.chat.R;

public class MainActivity extends AppCompatActivity {
    private EmbeddedIpfs embeddedIpfs;
    private WebView webView = null;
    private JavaScriptInterface jsInterface = null;
    private PeerId peerId = null;
    private Multihash otherNodeId = null;
    private PeerId otherPeerId = null;
    private AtomicBoolean initialised = new AtomicBoolean();

    public class Message {
        public final String id;
        public final String text;
        public final String author;
        public final LocalDateTime timestamp;
        public Message(String id, String text, String author, LocalDateTime timestamp) {
            this.id = id;
            this.text = text;
            this.author = author;
            this.timestamp = timestamp;
        }
        public Map<String, Object> toJson() {
            Map<String, Object> res = new HashMap<>();
            res.put("id", id);
            res.put("text", text);
            res.put("author", author);
            res.put("timestamp", timestamp.toString());
            return res;
        }
    }

    private final List<Message> messages = new ArrayList<>();

    public class JavaScriptInterface {
        private Activity activity;

        public JavaScriptInterface(Activity activity) {
            this.activity = activity;
        }
        @JavascriptInterface
        public String getNodeId() {
            return initialised.get() ? peerId.toBase58() : "";
        }

        @JavascriptInterface
        public void setOtherNodeId(String otherNodeIdStr) {
            otherNodeId = Multihash.fromBase58(otherNodeIdStr);
            otherPeerId = PeerId.fromBase58(otherNodeId.toBase58());
        }

        @JavascriptInterface
        public String getMessages(int fromIndex){
            List<Message> msgs = messages.stream().skip(fromIndex).collect(Collectors.toList());
            List<Map<String, Object>> json = msgs.stream().map(msg -> msg.toJson()).collect(Collectors.toList());
            return JSONParser.toString(json);
        }
        @JavascriptInterface
        public String addMessage(String body) {
            Map<String, Object> json = (Map) JSONParser.parse(body);
            String id = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            String msgText = (String)json.get("text");
            Message message = new Message(id, msgText, (String)json.get("author"), now);
            messages.add(message);
            try {
                proxyMessage(msgText, EmbeddedIpfs.getAddresses(embeddedIpfs.node, embeddedIpfs.dht, otherNodeId));
            } catch (ConnectionException ce) {
                ce.printStackTrace();
            }
            Map<String, Object> retJson = message.toJson();
            return JSONParser.toString(retJson);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        jsInterface = new JavaScriptInterface(this);
        this.webView = (WebView) findViewById(R.id.webview);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webView.addJavascriptInterface(jsInterface, "JSInterface");
        WebViewClientImpl webViewClient = new WebViewClientImpl(this);
        webView.setWebViewClient(webViewClient);
        webView.loadUrl("file:///android_asset/index.html");

        new Thread(new Runnable() {
            public void run() {
                try {
                    start();
                }catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }).start();
    }

    private void start() {
        RecordStore recordStore = new RamRecordStore();
        Blockstore blockStore = new RamBlockstore();

        int portNumber = 10000 + new Random().nextInt(50000);
        List<MultiAddress> swarmAddresses = new ArrayList<>();
        swarmAddresses.add(new MultiAddress("/ip6/::/tcp/" + portNumber));
        List<MultiAddress> bootstrapNodes = new ArrayList<>(Config.defaultBootstrapNodes);

        HostBuilder builder = new HostBuilder().generateIdentity();
        PrivKey privKey = builder.getPrivateKey();
        peerId = builder.getPeerId();
        IdentitySection identitySection = new IdentitySection(privKey.bytes(), peerId);
        BlockRequestAuthoriser authoriser = (c, b, a) -> CompletableFuture.completedFuture(true);

        HttpProtocol.HttpRequestProcessor proxyHandler = (s, req, h) -> {
            ByteBuf content = req.content();
            String output = content.getCharSequence(0, content.readableBytes(), Charset.defaultCharset()).toString();
            System.out.println("received msg:" + output);
            String author = "otherNode";
            jsInterface.addMessage("{\"author\":\"" + author + "\",\"text\":\"" + output + "\"}");

            FullHttpResponse replyOk = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.buffer(0));
            replyOk.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            h.accept(replyOk.retain());
        };

        embeddedIpfs = EmbeddedIpfs.build(recordStore, blockStore, false,
                swarmAddresses,
                bootstrapNodes,
                identitySection,
                authoriser, Optional.of(proxyHandler));
        embeddedIpfs.start();
        initialised.set(true);
    }
    private void proxyMessage(String message, Multiaddr[] addressesToDial) {
        byte[] msg = message.getBytes();
        FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Unpooled.copiedBuffer(msg));
        httpRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, msg.length);
        HttpProtocol.HttpController proxier = embeddedIpfs.p2pHttp.get().dial(embeddedIpfs.node, otherPeerId, addressesToDial).getController().join();
        proxier.send(httpRequest.retain()).join().release();
    }
}