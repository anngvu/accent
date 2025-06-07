## ACCENT

> [!WARNING]  
> This is **an application for research and rapid prototyping**, used to better understand and improve UX and mitigate risks of data professionals working with AI for data-related workflows. 

### Motivation

#### For people who do scientific data management/curation

Science is built upon data. Research communities have the goal of sharing data packaged and disseminated optimally for (re)use. 

If the community is lucky, this is supported by dedicated data curators/managers and contributing scientists are also directly involved. 

If the community is even luckier, good data curation/management tools are available for the processes of developing and applying a common data model, sharing data, finding data, assessing data, etc.

Like with other knowledge work, incorporating AI (just another tool that can use tools) could greatly boost productivity, though it is perhaps best achieved through an internal or "wrapper" interface that mitigate pitfalls[^1] and maximize ergonomics.

> Developers can also help with figuring out **where AI can be inserted into workflows and how to design technology for doing that**. 

Data management responsibilities[^2][^3] prioritized for an assisted workflow are: 
1. Data curation -- create, organize, QC, and publish FAIR/harmonized data assets to the best advantage. 
2. Develop standards and data models. 
3. Maintain data management plans and SOPs. 
4. Facilitate data analysis/reuse and reporting for stakeholders, regulatory authorities, etc. 
5. Integration of apps/new technologies and initiatives into data standards and structures. 

<!-- #### And for everyone

Everyone is a curator and could benefit from AI-assisted curation. This open-source application originally developed for biomedical data curation is actually quite reusable for other domains and personal use cases. Some "off-label" use cases will be demonstrated. -->

### Usage

Unlike using generative AI in the default web interface, the application infrastructure here adds prompts and logic already optimized to project-specific workflows, API access to relevant platforms (Synapse), ability to read local files, ability to read and write to embedded databases for RAG, and other tools and resources to accomplish various tasks in the realm of data curation/management.

There are several options to make use of these tools, resources, and prompts:

1. *accent* can be run as an MCP server so you can use your favorite MCP-compatible client. See [MCP Server mode](https://github.com/anngvu/accent?tab=readme-ov-file#MCP-server-mode).
2. If you don't have a preferred compatible client, *accent* does include a basic terminal interface and web app client interface. To use either of these, see [Built-in client interface](https://github.com/anngvu/accent?tab=readme-ov-file#Built-in-client-interface).

There is also a more detailed discussion of clients in doc/MCP.md that might be helpful to read. 

#### MCP Server mode

*accent* can be run as a local MCP server that you use with your preferred desktop client.

1. Download a jar release v0.6.0 or later from the [releases page](https://github.com/anngvu/accent/releases).
2. Follow the configuration instructions for your client. With [Claude for Desktop](https://modelcontextprotocol.io/quickstart/user#2-add-the-filesystem-mcp-server), use the configuration below:
```
{ 
  "mcpServers" {
    "accent": {
      "command": "java",
      "args": [
        "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/log4j2-factory",
        "-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog",
        "-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector",
        "-Dlog4j2.configurationFile=log4j2-mcp.xml",
        "-jar",
        "/Users/your/path/to/accent.jar",
        "mcp-server"
      ],
      "env": {  
        "SYNAPSE_AUTH_TOKEN": "your-synapse-pat"
      }
    },
    ...
  }
}
```

#### Built-in client interface

You can use the built-in client interface in *accent*, but note that they are rather minimal.

1. For the web app client interface, download a jar release v0.4.0 or later from the [releases page](https://github.com/anngvu/accent/releases).
2. Set up a config file in the same location as your jar. See [Configuration](https://github.com/anngvu/accent?tab=readme-ov-file#configuration).
3. **Choose whether you want to run the web app or console UI below:**

##### Web app

This is recommended for most users. Run the jar by double-clicking the file. This should open a browser window with your default browser app with Syndi as your assistant.

##### Console

**This requires Clojure dev tooling** and is only recommended if you're comfortable with Clojure (or want to be comfortable with Clojure) and want to test experimental features and hack with the interface/framework internals. 
It does give more control for running specific agents that might not be available in the web app.

1. Install Clojure.
2. Clone this repo.
3. Set up a config file at the root of the repo. See [Configuration](https://github.com/anngvu/accent?tab=readme-ov-file#configuration).
4. Run the desired agent module, e.g. `clj -M -m agents.syndi`.

#### Configuration

Settings and (optionally) credentials ares defined in `config.edn`. 
Review the `example_config.edn` file; rename it to `config.edn` and modify as needed. 

##### AI Providers specification in configuration

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


### Docs

- doc/AGENTS.md describes and explains more about agents and usage.
- doc/MCP.md describes and explains more about Model Context Protocol.
- doc/ROADMAP.md describes roadmap and context.
- doc/DESIGN.gv reflects a draft architecture of app.


### Demos and Tutorials (WIP)

Planned demo materials will be linked once available:
- **Assisted curation workflow** for preparing some kind of data asset for Synapse (e.g. a dataset).
- **Data model exploration and development** for working with different DCC-specific models to reuse concepts, maintain alignment, improve quality, etc.

<!--  ##### For personal knowledge curation 

TBD. -->


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
