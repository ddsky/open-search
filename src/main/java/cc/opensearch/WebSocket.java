package cc.opensearch;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import ws.palladian.persistence.json.JsonObject;

import java.io.IOException;

/**
 * This class handles web socket messages.
 *
 * @author David Urbansky
 * @since 02.01.2024 at 11:36
 **/

@org.eclipse.jetty.websocket.api.annotations.WebSocket
public class WebSocket {
    @OnWebSocketMessage
    public void handleTextMessage(Session session, String message) throws IOException {
        JsonObject jsonResponse;
        try {
            jsonResponse = Searcher.getInstance().search(message, session);
            String html = HtmlRenderer.getInstance().renderHtml(jsonResponse);
            session.getRemote().sendString(html);
        } catch (Exception e) {
            session.getRemote().sendString("Something went wrong");
        }
    }
    //    @OnWebSocketMessage
    //    public void handleBinaryMessage(Session session, byte[] buffer, int offset, int length) throws IOException {
    //        System.out.println("New Binary Message Received");
    //        session.getRemote().sendBytes(ByteBuffer.wrap(buffer));
    //    }
}
