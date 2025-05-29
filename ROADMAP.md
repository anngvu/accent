## Dynamic Roadmap

**Generative AI technology/application is a quickly changing field. 
This roadmap is highly adaptive based on other current developments, project needs, and feedback and interest received.** 

### Phase I

This phase was primarily concerned with implementation of a basic framework that:
- worked with at least two providers (OpenAI and Anthropic) 
- provided a basic client interface (console and web UI) for users, explorinng some potentially useful features of a custom client interface (charting, product staging)
- could be tested with workflows of interest (curation, data model RAG)

- **v0.01** - Undifferentiated infrastructure  
    - Basic state management for user/api tokens, model, messages
    - Integrate OpenAI APIs for selected ChatGPT models
    - Basic working chat through console (see POC roadmap where for later interface enhancements beyond console)
    - Simple function to save chats (Use case for this: For users, capture history for reference. For developers, help with testing and analysis)
    - Working project configuration and build scripts
- **v0.1** - First assisted workflow for dataset curation for **NF** use case.
    - Automatically pull in DCC configurations at startup -- we should know to use consistent DCC settings, and not have to specify them manually either
    - Add DCC configuration to state management
    - Implement integration of Synapse APIs needed for this curation workflow (querying and download)
    - Define basic prompts and wrappers for Synapse querying and curation workflow
    - Working `curate_dataset` function call
- **v0.2** - MVP for data model exploration and comparison for data models in the schematic JSON-LD with a chat interface (RAG), relevant to Responsibility 2.
    - Integrate a suitable local database solution
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
- **v0.6** - Agentic curation of external sources into structured format that can be stored as Synapse annotations.
    - Create annotation data (JSON) given content/content sources and a JSON schema.
    - Storage into Synapse or local file.

# Phase II

