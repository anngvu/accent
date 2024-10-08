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

To create a more useful and "responsible" wrapper interface (in several senses of the word "responsible"), the app builds structure around the responsibilities described above that match the org's current workflows. Unlike interacting with the LLM in the default interface from a model provider, we add new infra/integrations that make some things easier (e.g. prompt templates, tools use, and access to Synapse APIs) while at the same time trying to put in some guardrails.

#### AI Providers

The app integrates two providers, Anthropic and OpenAI. In the same conversation, it is possible to switch between models from the same provider, though not between different providers, e.g. switching from ChatGPT-3.5 to ChatGPT-4o is fine, but not from ChatGTP-3.5 to Claude Sonnet-3.5. However, just because the switching feature exists does not mean it is expected for the user to try manually try switching too much between models for different tasks. For both providers, the default is to use a model on the smarter end. Trying to reduce costs by switching to a cheaper model for some tasks is likely premature optimization at this early stage.

- OpenAI
  - To use, must have `OPENAI_API_KEY` in env/config.
  - The default is the ChatGPT-4o model.
- Anthropic
  - To use, must have `ANTHROPIC_API_KEY` in env/config.
  - The default is Claude Sonnet 3.5.


#### Structured modes

Here is how Responsibilities map to structured modes:

- **Assisted curation workflow** - You are preparing some kind of data asset for Synapse (e.g. a dataset).
- **Data model exploration and development**
schematic JSON-LD models** - You want to develop your DCC-specific model with the benefit of analytical capabilities and accessible context with other DCC models (to reuse concepts, maintain alignment, improve quality, etc.) 

Planned functionality have been scoped/mapped as below for specific versions. 
(This roadmap does change with feedback and outside suggestions.)

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
- **v0.4** - Basic interactive viz help, which serve all Responsibilities that benefit from easier analytics.
    - Integrate a basic package/solution for viz
    - Appropriate prompts and wrapper functionality for viz
    - Working example `visualize` function call for **data model**
    - Working example `visualize` function call for **dataset**
- **v0.5** - Implement upgraded interface as alternative to the basic console: TUI or simple web UI.


Nothing more is planned until after the Evaluation (below).

#### Evaluation

There are ideas for other helper workflows and functionality, but these are dependent on first round of the proof-of-concept feedback, in case this is not the right approach/the design needs to change significantly. 
To inform whether this actually benefits data management work, we need to to evaluate the proof-of-concept in several ways. 
We would have to ask a user, "How would you compare using this versus trying to accomplish **the same work goal** using a different workflow that": 
1. *Doesn't incorporate* any LLM and does things manually, with custom scripting, or with some other non-AI app.
2. Incorporates ChatGPT but via the default online chat interface.
3. Incorporates LLM/multiple LLMs through a different custom interface/solution.

There is also workflow-specific research needed. To be continued...

---

[^1]: https://mitsloan.mit.edu/ideas-made-to-matter/how-generative-ai-can-boost-highly-skilled-workers-productivity
[^2]: https://www.indeed.com/hire/job-description/data-manager#toc-jumpto-1
[^3]: https://www.icpsr.umich.edu/web/pages/datamanagement/index.html 
