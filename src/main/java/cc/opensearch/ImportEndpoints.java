package cc.opensearch;

import org.apache.log4j.Logger;
import ws.palladian.helper.io.FileHelper;
import ws.palladian.persistence.json.JsonArray;
import ws.palladian.persistence.json.JsonObject;
import ws.palladian.retrieval.DocumentRetriever;

/**
 * Automatically generate a list of API endpoints.
 *
 * @author David Urbansky
 * @since 21.12.2023 at 16:26
 **/
public class ImportEndpoints {
    public void generate() {
        // collect a simplified description of the available API endpoints
        StringBuilder apiEndpoints = new StringBuilder();

        String apisString = FileHelper.tryReadFileToString("data/public-rest-apis.json");
        JsonObject apiDefinitions = JsonObject.tryParse(apisString);
        JsonArray categories = apiDefinitions.tryGetJsonArray("item");
        for (int i = 0; i < categories.size(); i++) {
            JsonObject categoryJson = categories.tryGetJsonObject(i);
            JsonArray apiJsons = categoryJson.tryQueryJsonArray("item[0]/item");
            if (apiJsons == null) {
                apiJsons = categoryJson.tryGetJsonArray("item");;
            }
            for (int j = 0; j < apiJsons.size(); j++) {
                JsonObject apiJson = apiJsons.tryGetJsonObject(j);
                JsonObject requestJson = apiJson.tryGetJsonObject("request");

                if (requestJson == null) {
                    continue;
                }

                // we only care about GET requests
                if (!"GET".equals(requestJson.tryGetString("method"))) {
                    continue;
                }

                // get the request URL
                String url = requestJson.tryQueryString("url/raw");

                // make a request to see whether it is still working
                try {
                    String response = new DocumentRetriever().getText(url);
                    if (response == null || response.isEmpty()) {
                        Logger.getRootLogger().error("API endpoint " + url + " is not working");
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }

                // replace the parameters with placeholders
//                JsonArray queryParameters = requestJson.tryGetJsonArray("query");
//                for (int k = 0; k < queryParameters.size(); k++) {
//                    queryParameters.tryGetJsonObject(k);
//                }

                String description = requestJson.tryGetString("description");
                description = description.replaceAll("\n.*", "");
                apiEndpoints.append(description).append("\n");
                apiEndpoints.append(url).append("\n");

                apiEndpoints.append("\n");
            }
        }

        FileHelper.writeToFile("data/api-endpoints.txt", apiEndpoints.toString());
    }

    public static void main(String[] args) {
        new ImportEndpoints().generate();
    }
}
