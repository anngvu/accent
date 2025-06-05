## Dynamic Roadmap

**Generative AI technology/application is a quickly changing field. 
This roadmap is highly adaptive to emerging developments in the ecosystem. 
Direction also changes based on real usage, user feedback and requests. Every feature here should be considered alpha/unstable.**

### Phase I

This phase was primarily concerned with creating a basic chat/agent framework that:
- worked with at least two providers (OpenAI and Anthropic) 
- implemented relevant tools (primarily Synapse tools and embedded knowledge graph usage) and prompts
- provided a basic client interface (terminal and web UI, streaming + non-streaming) for users, though with potentially useful features for charts and product staging

- **v0.01** - Basic framework  
    - Basic state management/memory for user/api tokens, model, messages
    - Integrate OpenAI APIs for selected ChatGPT models
    - Basic working chat through console (see POC roadmap where for later interface enhancements beyond console)
    - Simple function to save chats (Use case for this: For users, capture history for reference. For developers, help with testing and analysis)
    - Working project configuration and build scripts
- **v0.1** - First assisted workflow for dataset curation for project-specific use case.
    - Automatically pull in DCC configurations at startup -- we should know to use consistent DCC settings, and not have to specify them manually either
    - Add DCC configuration to state management
    - Implement integration of Synapse APIs needed for this curation workflow (querying and download)
    - Define basic prompts and wrappers for Synapse querying and curation workflow
    - Working `curate_dataset` function call
- **v0.2** - MVP for data model exploration and comparison for data models in the schematic JSON-LD with a chat interface (RAG), relevant to Responsibility 2.
    - Integrate a suitable local database solution (Datalevin)
    - Implement ETL of data model graphs at startup
    - Implement database schemas, instantiation and management
    - Define some basic canned queries for model usage/training
    - Define appropriate prompts and wrapper functionality for RAG
    - Working `ask_database` function call
- **v0.3** - Enable another AI provider (Anthropic) for flexibility and potential benchmarking applications. 
    - Integrate Anthropic Claude models.
    - Parity in terms of tool use (function calling).
- **v0.4** - Implement upgraded UI / UX as an alternative to the basic console (simple web UI).
    - Set up local server.
    - Implement UI.
    - Implement streaming.
- **v0.5** - Basic interactive staging and visualization.
    - Integrate a basic package/solution for viz.
    - Appropriate prompts and wrapper functionality for viz.
    - Working example staging function call for **dataset**
    - Working example visualize function call for data charting.
- **v0.6** - Add agent (Arachne) to complement Syndi, with updated graph RAG.
    - Add Apache Jena as another connectable DB in addition to Datalevin (demonstrated in v0.2).
    - Update graph db instantation and design to accomodate new requirements (template transformations) and example data.
    - Implement new agent tools.

### Phase II

Given a shifting landscape, in Phase II we will be deprioritizing "client-side" features while continuing to focus on curation tools and optimal agentic composition/workflows; add new focus on MCP compatibility; adapt to new interest in adding another provider (Google); explore how to integrate symbolic AI and other types of AI as tools that can be used in tandem with commodity LLMs. 

The "client-side" features refer to features implemented in v0.4 and v0.5, which are focused on improving and adding features for our built-in custom interface. 
When the roadmap was first created, Claude Desktop client and especially Model Context Protocol did not yet exist, but it seems likely that users will eventualy be subscribed to some AI provider, and each provider will have a desktop client such as [Claude Desktop](https://claude.ai/download), [OpenAI Desktop](https://openai.com/chatgpt/desktop/), etc., with eventual MCP compatibility even if the provider does not currently have MCP compatibility. 

Some of these clients do very well at rendering charts and other visualizations so we don't need *accent* for this. 
So even though we added visualization to make *accent* more usable in an AI-enabled analysis workflow, today it wouldn't be the best client for visualizing things; its strengths should be in providing the data more easily (from Synapse or local files) or specialized tools that help with the analysis (e.g. validation and harmonization of clinical data when combining data in the analysis) -- and this can be done via MCP. 

- **v0.7** - Enable interopability via MCP; and more composable agent framework where agents can share tools. (Focus on interopability and composability.)
    - Implement global tools registry.
    - Add MCP server capability to serve tools.

- **v0.8** - Enable reusable curation workflows via MCP prompts. 
    - Agentic curation of external sources into structured format (e.g. Synapse metadata compliant with a data model).
