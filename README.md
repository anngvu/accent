## ACCENT

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

To create a more useful and "responsible" wrapper interface (in several senses of the word "responsible"), the app builds structure around the responsibilities described above that match the org's current workflows. Unlike interacting with the LLM in the default interface, this is basically interaction with additional configuration, involving specific prompt templates and tools use access.

#### Structured modes

Here is how Responsibilities map to structured modes:

- **Assisted curation workflow** - You are preparing some kind of data asset for Synapse (e.g. a dataset).
- **Data model exploration and development**
schematic JSON-LD models** - You want to develop your DCC-specific model with the benefit of analytical capabilities and accessible context with other DCC models (to reuse concepts, maintain alignment, improve quality, etc.) 

Planned functionality have been scoped/mapped as below for specific versions:

- **v0.01** - Undifferentiated infrastructure  
    - Basic state management for user/api tokens, model, messages
    - Basic working chat through console (see v0.4 for interface enhancements)
    - Simple function to save chats (for users: history for reference / for development: for testing, user research and analysis)
    - Working project configuration and build scripts
- **v0.1** - First assisted workflow for dataset curation for NF use case, under the umbrella of Responsibility 1.
    - Automatically pull in DCC configurations at startup -- we should know to use consistent DCC settings, and not have to specify them manually either
    - Add DCC configuration to state management
    - Implement low-level Synapse APIs needed for this curation
    - Basic flow control and prompts related to this workflow
    - Working `curate_dataset` function call
- **v0.2** - MVP for data model exploration and comparison for data models in the schematic JSON-LD with a chat interface (RAG), relevant to Responsibility 2.
    - ETL of data model graphs at startup
    - Local database instantiation and management
    - Appropriate prompts for RAG
    - Working `ask_database` function call
- **v0.3** - Basic interactive viz help, which serve all Responsibilities that benefit from easier analytics.
    - Appropriate prompts for viz
    - Example `visualize` function call for data model
    - Example `visualize` function call for dataset
- **v0.4** - Implement upgraded interface as alternative to the basic console: TUI or simple web UI.



Nothing more is planned until after the Evaluation (below).

#### Evaluation

There are ideas for other helper workflows and functionality, but these are dependent on first round of the proof-of-concept feedback, in case this is not the right approach/the design needs to change significantly. 
To inform whether this actually benefits data management work, we need to to evaluate the proof-of-concept in several ways. 
We would have to ask a user, "How would you compare using this versus trying to accomplish **the same work goal** using a different workflow that": 
1. *Doesn't incorporate* any LLM and does things manually, with custom scripting, or with some other non-AI app.
2. Incorporates ChatGPT but via the default online chat interface.
3. Incorporates LLM/multiple LLMs through a different custom interface/solution.

There is also workflow-specific research needed:

##### Data model exploration
- 

---

[^1]: https://mitsloan.mit.edu/ideas-made-to-matter/how-generative-ai-can-boost-highly-skilled-workers-productivity
[^2]: https://www.indeed.com/hire/job-description/data-manager#toc-jumpto-1
[^3]: https://www.icpsr.umich.edu/web/pages/datamanagement/index.html 
