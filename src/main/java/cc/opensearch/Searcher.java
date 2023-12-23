package cc.opensearch;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;
import ws.palladian.helper.ConfigHolder;
import ws.palladian.helper.UrlHelper;
import ws.palladian.helper.collection.MapBuilder;
import ws.palladian.helper.io.FileHelper;
import ws.palladian.helper.nlp.PatternHelper;
import ws.palladian.helper.nlp.StringHelper;
import ws.palladian.persistence.json.JsonArray;
import ws.palladian.persistence.json.JsonDatabase;
import ws.palladian.persistence.json.JsonObject;
import ws.palladian.retrieval.DocumentRetriever;
import ws.palladian.retrieval.search.web.OpenAiApi;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

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

    // some APIs need authentication, keep a map of domain => pair of parameter + key in here
    private final Map<String, Pair<String, String>> apiAuthentication = new HashMap<>();

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
        JsonArray availableApis = JsonArray.tryParse(FileHelper.readFileToString(classLoader.getResourceAsStream("apis.json")));
        availableApis = filterApisIfNoAuthenticationAvailable(availableApis);
        apiDescriptions = createApiDescriptions(availableApis, FileHelper.readFileToString(classLoader.getResourceAsStream("api-availability-prompt.txt")));
        apiUsagePrompt = FileHelper.readFileToString(classLoader.getResourceAsStream("api-usage-prompt.txt"));
        htmlRenderingPrompt = FileHelper.readFileToString(classLoader.getResourceAsStream("html-render-prompt.txt"));
    }

    private String createApiDescriptions(JsonArray availableApis, String prompt) {
        prompt += "\n\n";

        for (int i = 0; i < availableApis.size(); i++) {
            JsonObject apiJson = availableApis.tryGetJsonObject(i);
            prompt += apiJson.tryGetString("description") + "\n";
            prompt += apiJson.tryGetString("url") + "\n\n";
        }

        return prompt;
    }

    /**
     * Some APIs require authentication. If we don't have any authentication information, we filter out those APIs.
     */
    private JsonArray filterApisIfNoAuthenticationAvailable(JsonArray availableApis) {
        JsonArray filteredApis = new JsonArray();

        for (int i = 0; i < availableApis.size(); i++) {
            JsonObject apiJson = availableApis.tryGetJsonObject(i);
            String authenticationConfigKey = Optional.ofNullable(apiJson.tryGetString("authentication_config_key")).orElse("");
            if (authenticationConfigKey.isEmpty()) {
                filteredApis.add(apiJson);
            } else {
                Configuration config = ConfigHolder.getInstance().getConfig();
                String apiKey = config.getString("api." + authenticationConfigKey + ".key");
                if (apiKey != null && !apiKey.isEmpty()) {
                    apiAuthentication.put(config.getString("api." + authenticationConfigKey + ".domain"),
                            Pair.of(config.getString("api." + authenticationConfigKey + ".parameter"), apiKey));
                    filteredApis.add(apiJson);
                } else {
                    LOGGER.warn("no authentication information found for API: " + apiJson);
                }
            }
        }

        return filteredApis;
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

        // see whether we have authentication information for this domain
        String domain = UrlHelper.getDomain(apiUrl, false, false);
        if (apiAuthentication.containsKey(domain)) {
            Pair<String, String> authentication = apiAuthentication.get(domain);
            if (apiUrl.contains("?")) {
                apiUrl += "&";
            } else {
                apiUrl += "?";
            }
            apiUrl += authentication.getLeft() + "=" + authentication.getRight();
        }

        DocumentRetriever documentRetriever = new DocumentRetriever();
        documentRetriever.setGlobalHeaders(MapBuilder.createPut("Accept", "application/json").create());
        LOGGER.info("making API call: " + apiUrl);
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

        LOGGER.info("open ai returned: " + StringHelper.shortenEllipsis(apiResponse.toString(), 100));
        LOGGER.debug("open ai returned: " + apiResponse);

        return apiResponse;
    }

    public String renderHtml(JsonObject apiResponse) throws Exception {
        if (apiResponse == null) {
            return null;
        }
        JsonObject templateJson = jsonDatabase.getOne(TEMPLATES_COLLECTION, "source", apiResponse.tryGetString("source"));

        String htmlResponse;
        if (templateJson != null) {
            LOGGER.info("found HTML template in database");
            htmlResponse = templateJson.tryGetString("html");
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

            htmlResponse = openAiApi.chat(messages, 0., new AtomicInteger(), "gpt-4-1106-preview", 4095);

            LOGGER.info("open ai returned HTML: " + StringHelper.shortenEllipsis(htmlResponse.toString(), 100));
            LOGGER.debug("open ai returned HTML: " + htmlResponse);

            JsonObject template = new JsonObject();
            template.put("html", htmlResponse);
            template.put("source", apiResponse.tryGetString("source"));
            jsonDatabase.add(TEMPLATES_COLLECTION, template);
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

    public static void main(String[] args) throws Exception {
        Searcher searcher = new Searcher();
        JsonObject search = searcher.search("what cocktails can I make with rum?");
        System.out.println(search);
        String html = searcher.renderHtml(search);
        System.out.println(html);
    }
}
