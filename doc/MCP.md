## Model Context Protocol Notes

### As a server

*accent* can be run as a [Model Context Protocol](https://github.com/model-context-protocol/model-context-protocol) server, though currently only as a local server with `stdio` transport, due to limitaions in the Clojure framework used. 
However, it's likely that our Clojure SDK will add other transports that enable running as a remote MCP server.

#### What client to use?

You have the freedom to choose a client that fits your needs or current subscription; most MCP-compatible clients implement `stdio` transport. Here's a summary of clients given desired features and potential scenarios, and which have been tested with *accent* in MCP server mode.

| Feature | Example clients | Tested w/ accent in MCP server mode | Potential scenarios |
|---------|-----------------|------------------|-------|
| **Data Visualization** | Claude Desktop, Open WebUI, Cherry Studio | Claude Desktop | Combine data from Synapse with this local file and create a summary viz | 
| **Code Editing Interface** | Cursor, VS Code, Zed, Cline, Windsurf | Cursor | (Vibe coding) Create an app from the data uploaded to this Synapse project |
| **Unified Interface for Multiple AI Providers** | Cursor, Cherry Studio, Open WebUI, MindPal, CarrotAI | Cursor | |
| **Local LLM Compatibility** | Open WebUI, Cherry Studio, Ollama-based clients (oterm), FLUJO |  | |
| **Terminal/CLI Integration** | Claude Code, Console Chat GPT, OmniConnect, Goose | Goose | |
| **Agent/Workflow Builder Interface** | MindPal, FLUJO, Nerve |  |  |
| **Real-time Collaboration** | Zed, Cursor | | Potentially test Zed for real-time collaborative editing of data model with Human Curator #1, Human Curator #2, and AI assistant fetching results from semantic db |

### As a client

*accent* includes a basic built-in client, which unfortunately is not MCP-compatible, and which is also less robust, feature-rich, and safe compared to many of the full-fledged clients listed above. *accent* as a prototype framework and client was initially meant to be first tested by more technical internal users. This [previously presented analogy](https://docs.google.com/presentation/d/1mBfajkaEankhIVwp4XBeAIoL-8LOqERKniYZZXdcvzo/edit?usp=sharing) visualizes where *accent* currently stands. 

- Guardrails/safety/observability: Clients listed above do generally implement stricter tool usage guardrails. For example, in Claude for Desktop or Cursor, users can toggle tools available and must approve each AI tool usage. In *accent*, there is no UI/config to similarly constrain tools (yet), and while a minimal safety/observability feature exists for seeing every single tool call made, you may not have the chance to intercept it first. In the future, *accent*'s framework may include alternative ways to manage AI safety without having a human micromanage/approve each AI tool call decision.
- AI provider support: *accent* can work with two major AI providers, OpenAI and Anthropic, with better support for OpenAI. It currently lacks support for Google and local LLMs.
