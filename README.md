## ACCENT

> [!WARNING]  
> This is a **prototype** application.
> Development is still working mitigating risks of using generative AI. 
Our current target users are Sage staff or other data professionals, not (yet) general Synapse users.   


### Motivation

Research communities supported by dedicated data managers receive the benefit of having data packaged and disseminated optimally. 
Data managers themselves could benefit from tooling to facilitate their important and hard work of curating data, developing the data model, and facilitating data sharing in general. 
And like with other knowledge work, including AI could greatly boost productivity, though it is perhaps best achieved through an internal or "wrapper" interface that mitigate pitfalls[^1]. 
> Developers can also help with figuring out where AI can be inserted into workflows and how to design technology for doing that. 

This is the proof-of-concept for such an application. 

The design considered the different responsibilities of a data manager and if/how each can be prioritized for an assisted workflow. 
There are many responsibilities[^2][^3], but the general list can be refined and ranked based on the work at Sage: 
1. Data curation -- create, organize, QC, and publish data assets to the best advantage. 
2. Develop standards and data models. 
3. Maintain data management plans and SOPs. 
4. Facilitate data analysis/reuse and reporting for stakeholders, regulatory authorities, etc. 
5. Oversee the integration of apps/new technologies and initiatives into data standards and structures. 


**To be clear, the Assisted Curation/Content ENhancement Tool is a proof-of-concept CLI tool only focuses on helping with the first two responsibilities.** 
In its first iteration, ACCENT narrows down the scope of the curation assistance even further, to dataset curation for the NF-OSI use case. 
The idea is to work out the "wrapper" interface into a usable and productive workflow first. 

### Usage

With more power comes the need to be more "responsible". 
Unlike interacting with an LLM in the default cloud interface provided by a model provider, the interface here will provide a more powerful infrastructure such as prompts and logic already optimized to project-specific workflows, direct API access to relevant systems (Synapse), local file and database system access, and other tools/agents to accomplish various tasks. But the infrastructure will also need to include guardrails.

Until this is released as a .jar, you do need some Clojure tooling. 

- Clone this repo. 
- Install [Leiningen](https://leiningen.org/) (the easiest way to use Clojure).
- Run `lein deps` to install dependencies.
- Create a config file called `config.edn`. See the section [configuration](https://github.com/anngvu/accent/tree/web-ui?tab=readme-ov-file#configuration).

#### Configuration

Settings and (optionally) credentials can be defined in `config.edn`. 
Review the `example_config.edn` file; rename it to `config.edn` and modify as needed. 
In addition to the comments in the example file, more discussion is provided below.

##### AI Providers

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

#### Run your preferred UI

> [!NOTE]  
> Currently, there are some tradeoffs between the terminal vs web UI. The web UI will have some features that the terminal will not, such as showing figures. On the other hand, web UI only works with OpenAI for now.

For the terminal:
- `lein run -m accent.chat`

For the web UI:
- `lein run -m accent.app`
- Go to http://localhost:3000

#### Demos / tutorials for various scenarios (WIP)

Links and materials will be provided when these demos are available:

- **Assisted curation workflow** - You are preparing some kind of data asset for Synapse (e.g. a dataset).
- **Data model exploration and development** - You want to develop your DCC-specific model with the benefit of analytical capabilities and accessible context with other DCC models (to reuse concepts, maintain alignment, improve quality, etc.) 

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
- **v0.5** - Curation of external sources into structured format that can be stored into Synapse.
    - Functionality to create annotated data (as JSON) given:
      -  (required) Many web pages and JSON schema. Assess target web page and let user know when one is not feasible. 
      -  (maybe) A PDF and JSON schema.
    - Storage into Synapse. (Note: data *does not always* have to be put into Synapse, so this is decoupled.)
- **v0.6** - Basic interactive viz help, which serve all Responsibilities that benefit from easier analytics.
    - Integrate a basic package/solution for viz
    - Appropriate prompts and wrapper functionality for viz
    - Working example `visualize` function call for **data model**
    - Working example `visualize` function call for **dataset**


Nothing more is planned until after the Evaluation (below).

### Evaluation

There are ideas for other helper workflows and functionality, but these are dependent on first round of the proof-of-concept feedback, in case this is not the right approach/the design needs to change significantly. 
To inform whether this actually benefits data management work, we need to to evaluate the proof-of-concept in several ways. 
We would have to ask a user, "How would you compare using this versus trying to accomplish **the same work goal** using a different workflow that": 
1. *Doesn't incorporate* any LLM and does things manually, with custom scripting, or with some other non-AI app.
2. Incorporates ChatGPT but via the default online chat interface.
3. Incorporates LLM/multiple LLMs through a different custom interface/solution.

There is also workflow-specific research needed. To be continued...


[^1]: https://mitsloan.mit.edu/ideas-made-to-matter/how-generative-ai-can-boost-highly-skilled-workers-productivity
[^2]: https://www.indeed.com/hire/job-description/data-manager#toc-jumpto-1
[^3]: https://www.icpsr.umich.edu/web/pages/datamanagement/index.html 
