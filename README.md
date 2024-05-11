## ACCENT

### Motivation

Research communities supported by dedicated data managers receive the benefit of having data packaged and disseminated optimally.  
Data managers themselves could benefit from tooling to facilitate their important and hard work of curating data, developing the data model, and facilitating data sharing in general. 
And like with other knowledge work, including AI could greatly boost productivity, though it is perhaps best achieved through an internal or "wrapper" interface that mitigate pitfalls[^1]. 
This is the proof-of-concept for such an application.

The design considered the different responsibilities of a data manager and if/how each can be prioritized for an assisted workflow. 
There are many responsibilities[^2][^3], but the general list can be refined and ranked based on the work at Sage:
1. Data curation -- creating, organizing, and displaying/publishing data and metadata to the best advantage.
2. Develop standards and data models.
3. Maintain data management plans and SOPs.
4. Design and develop other data infrastructure to facilitate data analysis/reuse and reporting.
5. Oversee the integration of apps/new technologies and initiatives into data standards and structures. 


**To be clear, the Assisted Curation/Content ENhancement Tool is a proof-of-concept CLI tool only focuses on helping with the first two responsibilities.** 
In its first iteration, ACCENT narrows down the scope of the curation assistance even further, to dataset curation for the NF-OSI use case. 
The idea is to work out the "wraper" interface into a usable and productive workflow first. 

### Usage

There are different modes to operate in. The structured modes conceptualize the more "responsible" wrapper interface.

#### Unstructured modes:

- **Free chat** (default) - Start with normal chat, but from here you can transition to an existing structured mode by making your intention clear. 
You can also think of a structured mode as interacting with an AI under an additional, specific configuration (with specific prompt templates and tools use access).

#### Structured modes:

##### Planned

- **Assisted curation workflow** - You are preparing some kind of data asset for Synapse (e.g. a dataset).
- **Data model exploration and development with schematic JSON-LD models** - You want to develop your DCC-specific model with the benefit of analytical capabilities and accessible context with other DCC models (to reuse concepts, maintain alignment, improve quality, etc.) 

Planned functionality have been scoped/mapped as below for specific versions:

- **v0.1** - First assisted workflow for dataset curation for NF use case
- **v0.2** - MVP for data model exploration and comparison for data models in the schematic JSON-LD format (RAG)

More generalized curation workflows that works with any data asset and an arbitrary schema would be further down the line and dependent on the feedback for the proof-of-concept user feedback -- the design may significantly change.

---

[^1]: https://mitsloan.mit.edu/ideas-made-to-matter/how-generative-ai-can-boost-highly-skilled-workers-productivity
[^2]: https://www.indeed.com/hire/job-description/data-manager#toc-jumpto-1
[^3]: https://www.icpsr.umich.edu/web/pages/datamanagement/index.html 

