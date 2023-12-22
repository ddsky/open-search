package cc.opensearch;

import ws.palladian.helper.io.FileHelper;
import ws.palladian.persistence.json.JsonObject;

import static spark.Spark.*;

/**
 * Simple REST API.
 *
 * @author David Urbansky
 * @since 21.12.2023 at 21:20
 **/
public class Api {
    public static void main(String[] args) {
        // enable CORS
        before((request, response) -> {
            response.header("Access-Control-Allow-Origin", "*");
            response.header("Access-Control-Request-Method", "*");
            response.header("Access-Control-Allow-Headers", "*");
        });

        options("/*", (request, response) -> {
            String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");
            if (accessControlRequestHeaders != null) {
                response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }

            String accessControlRequestMethod = request.headers("Access-Control-Request-Method");
            if (accessControlRequestMethod != null) {
                response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }

            return "OK";
        });

        get("/responses/:id", (req, res) -> {
            res.type("application/json");
            String responseId = req.params(":id");
            return FileHelper.readFileToString("data/response" + responseId + ".json");
        });
        get("/search", (req, res) -> {
            res.type("text/html");
            JsonObject jsonResponse = Searcher.getInstance().search(req.queryParams("query"));
            String html = Searcher.getInstance().renderHtml(jsonResponse);
            return html;
        });
    }
}