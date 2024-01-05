# Open Search: AI and API powered search

The Open Search project aims to build a community driven search engine that is powered by a large language models and open APIs. See it in
on [opensearch.cc](https://opensearch.cc).

## Why?

Search engines are a critical part of the internet. They are the primary way that users find information. However, search engines are often controlled by a single entity and are
not transparent about how they work. This project aims to create a search engine that is transparent and community driven.

![OpenSearch in Action](assets/open-search-video.gif)

## How?

It works as follows:

1. A user enters a query
2. The query is sent to a large language model (e.g. Chat-GPT) to decide which API to use to resolve the query.
3. The query is sent to the API.
4. The API response is sent to the LLM again in order create an HTML response for the user. Alternatively, a user-crafted HTML template can be hydrated with the API response.

## Supported APIs

These [API Endpoints](src/main/resources/apis.json) are supported. If you would like to add an API, please submit a pull request.
Some APIs require an API key. The API key configuration is stored in [palladian.properties](src/main/resources/palladian.properties).

## Custom HTML Templates

By default, the API response is sent to the LLM again in order create an HTML response for the user. Alternatively, a user-crafted HTML template can be hydrated with the API
response. The templates are stored in [html-templates](src/main/resources/html-templates).
The file name has to be the domain of the API plus the path where all slashes are replaced by underscores (_).

For example, the API endpoint "https://api.gamebrain.co/games/search?query=strategy+games" is:

* Domain: api.gamebrain.co
* Path: /games/search
* => The template file name is: [api.gamebrain.co_games_search.html](src/main/resources/html-templates/api.gamebrain.co_games_search.html)

If a user enters a query that should be resolved by this API, the HtmlRenderer first looks whether a template exists for this API. If yes, the template is used to render the
response, if not, the LLM will be asked to render the response.

If you have created a template for an API, please submit a pull request.

## Run it locally

If you want to run everything locally, just follow these simple steps:

ordered list:

1. Clone the repository
2. Edit the [palladian.properties](src/main/resources/palladian.properties) file and add your API keys. Only one for one of the supported LLM APIs is required (
   either `api.openai.key` or `api.together.key`), all other are
   optional. The more APIs you add, the better the results will be as the LLM can choose from more APIs.
3. In your root folder, run `mvn clean install`
4. In your root folder, start the API `mvn exec:java -Dexec.mainClass=cc.opensearch.Api -o` or run the main method of `cc.opensearch.Api` in your IDE of choice
5. Open the [index.html](frontend/index.html) file in your browser. You can now enter queries and see the results.
6. Contribute! Let's make the search engine better together!


 
