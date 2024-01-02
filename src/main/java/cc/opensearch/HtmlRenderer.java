package cc.opensearch;

import org.apache.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import ws.palladian.helper.ConfigHolder;
import ws.palladian.helper.io.FileHelper;
import ws.palladian.helper.nlp.PatternHelper;
import ws.palladian.helper.nlp.StringHelper;
import ws.palladian.persistence.json.JsonArray;
import ws.palladian.persistence.json.JsonDatabase;
import ws.palladian.persistence.json.JsonObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The HTML renderer takes care of transforming a JSON response into a human-readable HTML response.
 *
 * @author David Urbansky
 * @since 02.01.2024
 **/
public class HtmlRenderer {
    private static final Logger LOGGER = Logger.getLogger(HtmlRenderer.class);
    private final boolean caching;
    private final JsonDatabase jsonDatabase;
    private static final String TEMPLATES_COLLECTION = "templates";

    private final String htmlRenderingPrompt;

    static class SingletonHolder {
        static HtmlRenderer instance = new HtmlRenderer();
    }

    public static HtmlRenderer getInstance() {
        return SingletonHolder.instance;
    }

    private HtmlRenderer() {
        caching = ConfigHolder.getInstance().getConfig().getBoolean("caching.html", false);

        if (caching) {
            jsonDatabase = new JsonDatabase("data", 1000);
            jsonDatabase.createIndex(TEMPLATES_COLLECTION, "source");
        } else {
            jsonDatabase = null;
        }

        ClassLoader classLoader = getClass().getClassLoader();
        htmlRenderingPrompt = FileHelper.readFileToString(classLoader.getResourceAsStream("html-render-prompt.txt"));
    }

    public String renderHtml(JsonObject apiResponse) throws Exception {
        return renderHtml(apiResponse, null);
    }

    public String renderHtml(JsonObject apiResponse, Session session) throws Exception {
        if (apiResponse == null) {
            return null;
        }
        JsonObject templateJson = null;

        if (caching) {
            templateJson = jsonDatabase.getOne(TEMPLATES_COLLECTION, "source", apiResponse.tryGetString("source"));
        }

        String htmlResponse;
        if (templateJson != null && templateJson.tryGetLong("expires", 0L) > System.currentTimeMillis()) {
            htmlResponse = templateJson.tryGetString("html");
            LOGGER.info("found HTML template in database: " + StringHelper.shortenEllipsis(htmlResponse, 100));
            session.getRemote().sendString("found cached HTML template");
        } else {
            JsonArray messages = new JsonArray();

            JsonObject systemMessage = new JsonObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", "We have the following API response:\n\n" + simplify(apiResponse));
            messages.add(systemMessage);

            JsonObject userMessage = new JsonObject();
            userMessage.put("role", "user");
            userMessage.put("content", htmlRenderingPrompt);
            messages.add(userMessage);

            if (session != null) {
                session.getRemote().sendString("asking LLM to render HTML");
            }
            htmlResponse = LargeLanguageModelApi.getInstance().chat(messages, 0., new AtomicInteger(), "gpt-4-1106-preview", 4095);

            LOGGER.info("LLM returned HTML: " + StringHelper.shortenEllipsis(htmlResponse.toString(), 100));
            LOGGER.debug("LLM returned HTML: " + htmlResponse);

            JsonObject template = new JsonObject();
            template.put("html", htmlResponse);
            template.put("source", apiResponse.tryGetString("source"));
            if (caching) {
                template.put("expires", System.currentTimeMillis() + TimeUnit.DAYS.toMillis(ConfigHolder.getInstance().getConfig().getInt("caching.duration_hours")));
                jsonDatabase.add(TEMPLATES_COLLECTION, template);
            }
        }

        long responseId = System.currentTimeMillis();
        String responseJsonFileName = "response" + responseId + ".json";
        FileHelper.writeToFile("data/" + responseJsonFileName, apiResponse.toString());
        String html = StringHelper.getSubstringBetween(htmlResponse, "```html", "```");

        // replace response section with actual response
        html = PatternHelper.compileOrGet("const response.*?response.json\\(\\);", Pattern.DOTALL).matcher(html).replaceAll("#__#;");
        html = html.replace("#__#", "this.jsonData=" + apiResponse);

        return html;
    }

    /**
     * Simplify the API response to make it shorter since we send it to the LLM and it costs tokens.
     */
    private String simplify(JsonObject apiResponse) {
        // shorten all arrays on the root level to max 5 elements
        for (String key : apiResponse.keySet()) {
            if (apiResponse.tryGetJsonArray(key) != null) {
                JsonArray array = apiResponse.tryGetJsonArray(key);
                if (array.size() > 5) {
                    JsonArray newArray = new JsonArray();
                    for (int i = 0; i < 5; i++) {
                        newArray.add(array.get(i));
                    }
                    apiResponse.put(key, newArray);
                }
            }
        }
        return apiResponse.toString();
    }
}
