package cc.opensearch;

import org.apache.log4j.Logger;
import ws.palladian.helper.ConfigHolder;
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
        jsonDatabase.createIndex("responses", "_id");

        ClassLoader classLoader = getClass().getClassLoader();
        apiDescriptions = FileHelper.readFileToString(classLoader.getResourceAsStream("apis.txt"));
        apiUsagePrompt = FileHelper.readFileToString(classLoader.getResourceAsStream("api-usage-prompt.txt"));
        htmlRenderingPrompt = FileHelper.readFileToString(classLoader.getResourceAsStream("html-render-prompt.txt"));
    }

    public JsonObject search(String query) throws Exception {
        String jsKey = StringHelper.makeSafeName(query);

        // see whether we have a response for the query already
        JsonObject apiResponse = jsonDatabase.getOne("responses", "_id", jsKey);
        if (apiResponse != null) {
            LOGGER.info("found response in database");
            return apiResponse;
        }

        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", apiDescriptions);
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.put("role", "user");
        userMessage.put("content", apiUsagePrompt.replace("#QUERY#", query));
        messages.add(userMessage);

        String response = openAiApi.chat(messages, 0., new AtomicInteger(), "gpt-4-1106-preview", 4095);
        //        String response = openAiApi.chat(messages, 0., new AtomicInteger());

        apiResponse = new DocumentRetriever().tryGetJsonObject(response);
        apiResponse.put("_id", jsKey);

        jsonDatabase.add("responses", apiResponse);

        LOGGER.info("open ai returned: " + apiResponse);

        return apiResponse;
    }

    public String renderHtml(JsonObject apiResponse) throws Exception {
        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", "We have the following API response:\n\n" + apiResponse.toString());
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.put("role", "user");
        userMessage.put("content", htmlRenderingPrompt);
        messages.add(userMessage);

        String response = openAiApi.chat(messages, 0., new AtomicInteger(), "gpt-4-1106-preview", 4095);

        long responseId = System.currentTimeMillis();
        String responseJsonFileName = "response" + responseId + ".json";
        FileHelper.writeToFile("data/"+responseJsonFileName, apiResponse.toString());
        String html = StringHelper.getSubstringBetween(response, "```html", "```");
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
