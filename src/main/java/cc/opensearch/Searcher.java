package cc.opensearch;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import ws.palladian.helper.ConfigHolder;
import ws.palladian.helper.UrlHelper;
import ws.palladian.helper.collection.MapBuilder;
import ws.palladian.helper.io.FileHelper;
import ws.palladian.helper.nlp.StringHelper;
import ws.palladian.persistence.json.JsonArray;
import ws.palladian.persistence.json.JsonDatabase;
import ws.palladian.persistence.json.JsonObject;
import ws.palladian.retrieval.DocumentRetriever;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Searcher takes a query, picks an API and returns the response.
 *
 * @author David Urbansky
 * @since 21.12.2023 at 16:27
 **/
public class Searcher {
    private static final Logger LOGGER = Logger.getLogger(Searcher.class);
    private final boolean caching;
    private final JsonDatabase jsonDatabase;
    private static final String RESPONSES_COLLECTION = "responses";

    private final String apiDescriptions;

    private final String apiUsagePrompt;

    /** some APIs need authentication, keep a map of domain => pair of parameter + key in here */
    private final Map<String, Pair<String, String>> apiAuthentication = new HashMap<>();

    static class SingletonHolder {
        static Searcher instance = new Searcher();
    }

    public static Searcher getInstance() {
        return SingletonHolder.instance;
    }

    private Searcher() {
        caching = ConfigHolder.getInstance().getConfig().getBoolean("caching.json", false);

        if (caching) {
            jsonDatabase = new JsonDatabase("data", 1000);
            jsonDatabase.createIndex(RESPONSES_COLLECTION, "_id");
        } else {
            jsonDatabase = null;
        }

        ClassLoader classLoader = getClass().getClassLoader();
        JsonArray availableApis = JsonArray.tryParse(FileHelper.readFileToString(classLoader.getResourceAsStream("apis.json")));
        availableApis = filterApisIfNoAuthenticationAvailable(availableApis);
        apiDescriptions = createApiDescriptions(availableApis, FileHelper.readFileToString(classLoader.getResourceAsStream("api-availability-prompt.txt")));
        apiUsagePrompt = FileHelper.readFileToString(classLoader.getResourceAsStream("api-usage-prompt.txt"));
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

    /**
     * Find the API that is best suited for the query.
     */
    private String getApiUrl(String query) throws Exception {
        JsonArray messages = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", apiDescriptions);
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.put("role", "user");
        userMessage.put("content", apiUsagePrompt.replace("#QUERY#", query));
        messages.add(userMessage);

        return LargeLanguageModelApi.getInstance().chat(messages, 0., new AtomicInteger(), "gpt-4-1106-preview", 4095);
    }

    public JsonObject search(String query) throws Exception {
        return search(query, null);
    }

    public JsonObject search(String query, Session session) throws Exception {
        String jsKey = StringHelper.makeSafeName(query);

        // see whether we have a response for the query already
        JsonObject apiResponse = null;

        if (caching) {
            apiResponse = jsonDatabase.getOne(RESPONSES_COLLECTION, "_id", jsKey);
        }
        if (apiResponse != null && apiResponse.tryGetLong("expires", 0L) > System.currentTimeMillis()) {
            if (session != null) {
                session.getRemote().sendString("Found a cached response");
            }
            LOGGER.info("found response in database");
            return apiResponse;
        }
        apiResponse = new JsonObject();

        String apiUrl = getApiUrl(query);
        if (apiUrl == null || !apiUrl.startsWith("http")) {
            LOGGER.error("AI response not a URL: " + apiUrl);
            if (session != null) {
                session.getRemote().sendString("Could not find API to resolve query");
            }
            return null;
        }
        if (session != null) {
            session.getRemote().sendString("Using API: " + apiUrl);
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
            apiUrl += authentication.getLeft() + "=" + UrlHelper.encodeParameter(authentication.getRight());
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

        if (caching) {
            apiResponse.put("_id", jsKey);
            apiResponse.put("expires", System.currentTimeMillis() + TimeUnit.DAYS.toMillis(ConfigHolder.getInstance().getConfig().getInt("caching.duration_hours")));
            apiResponse.put("source", apiUrl.replaceAll("\\?.*", ""));
            jsonDatabase.add(RESPONSES_COLLECTION, apiResponse);
        }

        LOGGER.info("open ai returned: " + StringHelper.shortenEllipsis(apiResponse.toString(), 100));
        LOGGER.debug("open ai returned: " + apiResponse);

        return apiResponse;
    }

    public static void main(String[] args) throws Exception {
        Searcher searcher = new Searcher();
        JsonObject searchResponse = searcher.search("what cocktails can I make with rum?");
        System.out.println(searchResponse);
        String html = HtmlRenderer.getInstance().renderHtml(searchResponse);
        System.out.println(html);
    }
}
