package cc.opensearch;

import org.apache.log4j.Logger;
import ws.palladian.helper.ConfigHolder;
import ws.palladian.helper.UrlHelper;
import ws.palladian.helper.collection.MapBuilder;
import ws.palladian.helper.io.FileHelper;
import ws.palladian.helper.nlp.StringHelper;
import ws.palladian.persistence.json.JsonArray;
import ws.palladian.persistence.json.JsonDatabase;
import ws.palladian.persistence.json.JsonObject;
import ws.palladian.retrieval.DocumentRetriever;
import ws.palladian.retrieval.search.web.OpenAiApi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Searcher takes a query, picks an API and returns the response.
 *
 * @author David Urbansky
 * @since 21.12.2023 at 16:27
 **/
public class Searcher {
    private static final Logger LOGGER = Logger.getLogger(Searcher.class);
    private final JsonDatabase jsonDatabase;
    private static final String RESPONSES_COLLECTION = "responses";
    private static final String TEMPLATES_COLLECTION = "templates";
    private final OpenAiApi openAiApi;
    private final String apiDescriptions;

    private final String apiUsagePrompt;
    private final String htmlRenderingPrompt;

    static class SingletonHolder {
        static Searcher instance = new Searcher();
    }

    public static Searcher getInstance() {
        return SingletonHolder.instance;
    }

    private Searcher() {
        openAiApi = new OpenAiApi(ConfigHolder.getInstance().getConfig());

        jsonDatabase = new JsonDatabase("data", 1000);
        jsonDatabase.createIndex(RESPONSES_COLLECTION, "_id");
        jsonDatabase.createIndex(TEMPLATES_COLLECTION, "source");

        ClassLoader classLoader = getClass().getClassLoader();
        apiDescriptions = FileHelper.readFileToString(classLoader.getResourceAsStream("apis.txt"));
        apiUsagePrompt = FileHelper.readFileToString(classLoader.getResourceAsStream("api-usage-prompt.txt"));
        htmlRenderingPrompt = FileHelper.readFileToString(classLoader.getResourceAsStream("html-render-prompt.txt"));
    }

    /**
     * Some APIs require authentication. If we don't have any authentication information, we filter out those APIs.
     */
    private void filterApisIfNoAuthenticationAvailable() {
        // FIXME
    }


    public JsonObject search(String query) throws Exception {
        String jsKey = StringHelper.makeSafeName(query);

        // see whether we have a response for the query already
        JsonObject apiResponse = jsonDatabase.getOne(RESPONSES_COLLECTION, "_id", jsKey);
        if (apiResponse != null) {
            LOGGER.info("found response in database");
            return apiResponse;
        }
        apiResponse = new JsonObject();

        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", apiDescriptions);
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.put("role", "user");
        userMessage.put("content", apiUsagePrompt.replace("#QUERY#", query));
        messages.add(userMessage);

        String apiUrl = openAiApi.chat(messages, 0., new AtomicInteger(), "gpt-4-1106-preview", 4095);

        if (apiUrl == null || !apiUrl.startsWith("http")) {
            LOGGER.error("AI response not a URL: " + apiUrl);
            return null;
        }

        DocumentRetriever documentRetriever = new DocumentRetriever();
        documentRetriever.setGlobalHeaders(MapBuilder.createPut("Accept", "application/json").create());
        String textResponse = documentRetriever.getText(apiUrl);
        if (textResponse == null) {
            LOGGER.error("API response not valid: " + textResponse);
            return null;
        } else {
            if (textResponse.startsWith("[")) {
                JsonArray array = JsonArray.tryParse(textResponse);
                apiResponse.put("response", array);
            } else {
                apiResponse = JsonObject.tryParse(textResponse);
            }
        }
        if (apiResponse == null) {
            LOGGER.error("API response not valid: " + textResponse);
            return null;
        }
        apiResponse.put("_id", jsKey);
        apiResponse.put("source", apiUrl.replaceAll("\\?.*", ""));

        jsonDatabase.add(RESPONSES_COLLECTION, apiResponse);

        LOGGER.info("open ai returned: " + apiResponse);

        return apiResponse;
    }

    public String renderHtml(JsonObject apiResponse) throws Exception {
        if (apiResponse == null) {
            return null;
        }
        JsonObject templateJson = jsonDatabase.getOne(TEMPLATES_COLLECTION, "source", apiResponse.tryGetString("source"));

        String htmlResponse;
        if (templateJson != null) {
            htmlResponse = templateJson.tryGetString("html");
        } else {
            JsonArray messages = new JsonArray();

            JsonObject systemMessage = new JsonObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", "We have the following API response:\n\n" + apiResponse);
            messages.add(systemMessage);

            JsonObject userMessage = new JsonObject();
            userMessage.put("role", "user");
            userMessage.put("content", htmlRenderingPrompt);
            messages.add(userMessage);

            htmlResponse = openAiApi.chat(messages, 0., new AtomicInteger(), "gpt-4-1106-preview", 4095);

            JsonObject template = new JsonObject();
            template.put("html", htmlResponse);
            template.put("source", apiResponse.tryGetString("source"));
            jsonDatabase.add(TEMPLATES_COLLECTION, template);
        }

        long responseId = System.currentTimeMillis();
        String responseJsonFileName = "response" + responseId + ".json";
        FileHelper.writeToFile("data/"+responseJsonFileName, apiResponse.toString());
        String html = StringHelper.getSubstringBetween(htmlResponse, "```html", "```");
        html = html.replace("'response.json'", "'responses/" + responseId + "'");

        LOGGER.info("open ai returned HTML: " + html);

        return html;
    }

    public static void main(String[] args) throws Exception {
        Searcher searcher = new Searcher();
        JsonObject search = searcher.search("what cocktails can I make with rum?");
        System.out.println(search);
        String html = searcher.renderHtml(search);
        System.out.println(html);
    }
}
