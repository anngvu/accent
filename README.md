## ACCENT

> [!WARNING]  
> This is a **prototype** application.
> Development is still working mitigating risks of using generative AI. 
Our current target users are data professionals.   

### Motivation

#### For biomedical curators/data managers

Research communities supported by dedicated data curators/managers receive the benefit of having data packaged and disseminated optimally for reuse. 
Data managers themselves could benefit from tooling to facilitate their important and hard work of curating data, developing the data model, and facilitating data sharing in general. 
And like with other knowledge work, including AI could greatly boost productivity, though it is perhaps best achieved through an internal or "wrapper" interface that mitigate pitfalls[^1]. 
> Developers can also help with figuring out where AI can be inserted into workflows and how to design technology for doing that. 

This is such an application. Some data management responsibilities[^2][^3] prioritized for an assisted workflow are: 
1. Data curation -- create, organize, QC, and publish FAIR/harmonized data assets to the best advantage. 
2. Develop standards and data models. 
3. Maintain data management plans and SOPs. 
4. Facilitate data analysis/reuse and reporting for stakeholders, regulatory authorities, etc. 
5. Oversee the integration of apps/new technologies and initiatives into data standards and structures. 

<!-- #### And for everyone

Everyone is a curator and could benefit from AI-assisted curation. This open-source application originally developed for biomedical data curation is actually quite reusable for other domains and personal use cases. Some "off-label" use cases will be demonstrated. -->

### Usage

With more power comes more responsibility. 
Unlike interacting with generative AI in the default web interface, the application infrastructure here includes prompts and logic already optimized to project-specific workflows, direct API access to relevant platforms (Synapse), the local file system, configured databases, and additional tools/agents to accomplish various tasks. This infrastructure will also need to include guardrails.

Regular end users should download and use the artifact .jar.

##### With a release jar (most end users)

- Download some available release from the [releases page](https://github.com/anngvu/accent/releases).
- Set up a config file in the same location as your jar. See [Configuration](https://github.com/anngvu/accent?tab=readme-ov-file#configuration).
- Run the jar, e.g. `java -jar accent-{version}.jar` or double-clicking the file. 

By default, this should open a web app with your default browser with Syndi as your assistant.

##### With Clojure dev tooling

If you're comfortable with Clojure (or want to be comfortable with Clojure) and enable additional hacking:

- Clone this repo. 
- Install [Leiningen](https://leiningen.org/) (the easiest way to use Clojure).
- Run `lein deps` to install dependencies.
- Create a config file called `config.edn`. See [Configuration](https://github.com/anngvu/accent?tab=readme-ov-file#configuration).
- Choose UI:
  - For web UI, which is recommended and already default: `lein run -m accent.app`
  - Alternatively, the terminal console/REPL currently allows different agents other than Syndi, or to interact with different module functions directly.
    
#### Configuration

Settings and (optionally) credentials can be defined in `config.edn`. 
Review the `example_config.edn` file; rename it to `config.edn` and modify as needed. 
In addition to the comments in the example file, more discussion is provided below.

##### AI Providers specification

> [!NOTE]  
> Only OpenAI works with *both* web app UI and developer console for now. Anthropic only works with the developer console.

The app integrates two providers, Anthropic and OpenAI, and an initial model provider must be specified. 
In the *same chat*, it is possible to switch between models from the *same provider* but not between different providers, e.g. switching from ChatGPT-3.5 to ChatGPT-4o, but not from ChatGTP-3.5 to Claude Sonnet-3.5. 
However, existence of the switching feature does not suggest that the user should be manually and frequently switching between models. 
For both providers, the default is to use a model on the smarter end, though later on it may be possible to specify an initial model in the config. 
Tip for usage: Trying to reduce costs by switching to a cheaper model for some tasks is likely premature optimization at this early stage. 

- OpenAI
  - To use, must have `OPENAI_API_KEY` in env or set in config.
  - The default model is ChatGPT-4o.
- Anthropic
  - To use, must have `ANTHROPIC_API_KEY` in env or set in config.
  - The default model is Claude Sonnet 3.5.

#### Demos and Tutorials (WIP)

Planned demo materials will be linked once available:
- **Assisted curation workflow** for preparing some kind of data asset for Synapse (e.g. a dataset).
- **Data model exploration and development** for working with different DCC-specific models to reuse concepts, maintain alignment, improve quality, etc.

<!--  ##### For personal knowledge curation 

TBD. -->

### Dynamic Roadmap

**This roadmap adapts to the feedback and interest received.** 
Functionality have been scoped/mapped as below for specific versions. 
Feel free to propose a new feature or fast-tracking an existing one. 

- **v0.01** - Undifferentiated infrastructure  
    - Basic state management for user/api tokens, model, messages
    - Integrate OpenAI APIs for selected ChatGPT models
    - Basic working chat through console (see POC roadmap where for later interface enhancements beyond console)
    - Simple function to save chats (Use case for this: For users, capture history for reference. For developers, help with testing and analysis)
    - Working project configuration and build scripts
- **v0.1** - First assisted workflow for dataset curation for **NF** use case, under the umbrella of Responsibility 1.
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
- **v0.6** - Curation of external sources into structured format that can be stored as Synapse annotations.
    - Functionality to create annotation data (JSON) given content/content source and a JSON schema:
      -  (required) A scrapable web page and JSON schema. Assess target web page and let user know when one is not feasible. 
      -  (maybe) A local text source and JSON schema.
    - Storage into Synapse. (Note: data *does not always* have to be put into Synapse, so this is decoupled.)



Nothing more is planned until after the Evaluation (below).

### Evaluation

Feedback is currently being gathered with curators who are being trained for integrating this into their workflows. 
The comparisons will be between workflows that:
1. *Doesn't incorporate* any LLM and does things manually, maybe with custom scripting, or with some other non-AI app.
2. Incorporates generative AI but only via the default online chat interface.
3. Incorporates generative AI through a different custom interface/solution.


[^1]: https://mitsloan.mit.edu/ideas-made-to-matter/how-generative-ai-can-boost-highly-skilled-workers-productivity
[^2]: https://www.indeed.com/hire/job-description/data-manager#toc-jumpto-1
[^3]: https://www.icpsr.umich.edu/web/pages/datamanagement/index.html 
