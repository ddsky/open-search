package cc.opensearch;

import org.apache.commons.configuration.Configuration;
import ws.palladian.helper.ConfigHolder;
import ws.palladian.persistence.json.JsonArray;
import ws.palladian.retrieval.search.web.OpenAiApi;
import ws.palladian.retrieval.search.web.TogetherApi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class wraps large language model APIs: OpenAI and Together AI for open source models.
 *
 * @author David Urbansky
 * @since 02.01.2024 at 11:16
 **/
public class LargeLanguageModelApi {
    /** Open AI */
    private final OpenAiApi openAiApi;

    /** Together AI to use open source models */
    private final TogetherApi togetherApi;

    static class SingletonHolder {

        static LargeLanguageModelApi instance = new LargeLanguageModelApi();
    }

    public static LargeLanguageModelApi getInstance() {
        return LargeLanguageModelApi.SingletonHolder.instance;
    }

    private LargeLanguageModelApi() {
        // check for which API we have an API key
        Configuration config = ConfigHolder.getInstance().getConfig();
        if (config.containsKey(OpenAiApi.CONFIG_API_KEY)) {
            openAiApi = new OpenAiApi(config);
        } else {
            openAiApi = null;
        }
        if (config.containsKey(TogetherApi.CONFIG_API_KEY)) {
            togetherApi = new TogetherApi(config);
        } else {
            togetherApi = null;
        }
    }

    public String chat(JsonArray messages, double temperature, AtomicInteger usedTokens, int maxTokens) throws Exception {
        Configuration config = ConfigHolder.getInstance().getConfig();
        if (openAiApi != null) {
            return openAiApi.chat(messages, temperature, usedTokens, config.getString("api.openai.model"), maxTokens);
        }
        if (togetherApi != null) {
            return togetherApi.chat(messages, temperature, usedTokens, config.getString("api.together.model"), maxTokens);
        }
        return null;
    }
}
