## Model Context Protocol Notes

### As an MCP server

*accent* can run in [Model Context Protocol](https://github.com/model-context-protocol/model-context-protocol) server mode, though currently only as a local server with `stdio` transport, due to limitaions in the Clojure framework. 
However, it's likely that our Clojure SDK will later add other transports that enable running as a remote MCP server.

#### What client to use?

You have the freedom to choose a client that fits your needs or current subscription; most MCP-compatible clients implement `stdio` transport. 
Here's a summary of popular clients given desired features and potential scenarios, and which have been tested with *accent* in MCP server mode.

| Feature | Example clients | Tested w/ *accent* MCP server | Potential scenario |
|---------|-----------------|------------------|-------|
| **Data Visualization** | Claude Desktop, Open WebUI, Cherry Studio | Claude Desktop | Combine data from Synapse with this local file and create a summary viz | 
| **Code Editing Interface** | Cursor, VS Code, Zed, Cline, Windsurf | Cursor | (Vibe coding) Create an app from the data uploaded to this Synapse project |
| **Unified Interface for Multiple AI Providers** | Cursor, Cherry Studio, Open WebUI, MindPal, CarrotAI | Cursor | |
| **Local LLM Compatibility** | Open WebUI, Cherry Studio, Ollama-based clients (oterm), FLUJO |  | |
| **Terminal/CLI Integration** | Claude Code, Console Chat GPT, OmniConnect, Goose | Goose | |
| **Agent/Workflow Builder Interface** | MindPal, FLUJO, Nerve |  |  |
| **Real-time Collaboration** | Zed, Cursor | | Potentially test Zed for real-time collaborative editing of data model with Human Curator #1, Human Curator #2, and AI assistant fetching results from semantic db |

#### Basic configuration for surfacing tools

*accent* tries to be intelligent about serving tools that you can actually use (via your AI assistant). 
For example, Synapse tools require Synapse credentials.

When adding *accent* to your client configuration, **note some environmental variables that affect what you'll see in your client**.

- `SYNAPSE_AUTH_TOKEN`: Set this to see Synapse tools in your client.

<!-- in progress 

- `FUTURE_HOUSE_KEY`: Provide this to see Future House tools (agents) in your client.

#### Other advanced configuration options for MCP server

There are advanced configuration options available when adding *accent* to your client configuration, explained below. 

- `ARACHNE_DATA_PATH`: *accent* includes a pre-built local semantic graph knowledgebase of data models/standards and bioinformatics knowledge as one of its tools. 
However, the pre-built knowledgebase can become out-of-date, or you might want to build your own knowledgebase with additional custom data. 
An advanced option for updating the knowledgebase without having to download a newer JAR release is to set the `ARACHNE_DATA_PATH` environment variable to the path of your local data files. 
Note: if using the *accent* remote server when it is available, being out-of-date would not be an issue.

- `VERTEX_API_KEY`: Google Vertex AI API key. Provide this to make available semantic indexing tools. This is required for the `DOCUMENT_LIBRARY_PATH` option to work.

- `DOCUMENT_LIBRARY_PATH`: *accent* can also create a knowledgebase with your local collection of documents stored at a specified directory, if this path is set. 
This REQUIRES that a working `VERTEX_API_KEY` is also set. You may also want to set the `DOCUMENT_DB_PATH`to control where the output document db is saved.
Note also that only files with `.txt`, `.md`, `.pdf`, `.docx`, and `.html` extensions are supported.

- `DOCUMENT_DB_PATH`: Path to dump the output db representing the document library. By default, the db is saved in the same directory as the `DOCUMENT_LIBRARY_PATH`.

-->

### As an MCP client

*accent*'s basic built-in client framework is unfortunately **not MCP-compatible**, and is also less robust, feature-rich, and safe compared to many of the full-fledged clients listed above. 
*accent*'s built-in client is meant more for technical internal users and research purposes. This [previously presented analogy](https://docs.google.com/presentation/d/1mBfajkaEankhIVwp4XBeAIoL-8LOqERKniYZZXdcvzo/edit?usp=sharing) visualizes where the *accent* client framework currently stands. 

- **Guardrails/safety/observability**: Clients listed above do generally implement stricter tool usage guardrails. For example, in Claude for Desktop or Cursor, users can toggle tools available and must approve each AI tool usage. 
In *accent*, there is no similar UI/config to similarly constrain tool access (yet), and while a minimal safety/observability feature exists for seeing each tool call made, you may not have the chance to intercept it first. 
In the future, *accent*'s framework may include alternative ways to manage AI safety without having a human micromanage/approve each AI tool call decision.

- **AI provider support**: *accent* can work with two major AI providers, OpenAI and Anthropic, with better support for OpenAI. 
It currently lacks support for Google and local LLMs.

### Other important questions and answers

- Is there a difference in the tools available when running *accent* as an MCP server with an external client vs. its built-in client?

Yes, sometimes the tools available may be different. 
The MCP server may not export more experimental tools that would be otherwise be callable through the built-in client. 
Also, some tools may only intended to work with the built-in client. 
