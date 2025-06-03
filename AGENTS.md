## Agents

This document conceptualizes the agents implemented within the *accent* application. 
Each agent fills a role and has "a very particular set of skills". 
Agents use pre-assigned commodity models that are believed to work well enough to fill each of these roles. 

Agents are in highly active development; their design evolves as our tests and research reveals:
- More optimized role assigments for commercial provider models.
- Tools that make most sense with the agent and the actual user interactions and workflows.

### Syndi (Synapse Agent)

Syndi is a local agent that can help users with Synapse and common data-related workflows. 
She has access to common functions for interacting Synapse directly, and for other things can work with a predefined set of agents (at the moment, Syndi cannot be connected to a new agent dynamically). 
"Connecting" Syndi to a new agent is a matter of adding the interface to another agent. 
While Syndi currently uses an OpenAI model, that may not be the case later on. 
It is expected that there *are* limits on the number of agents that Syndi can interface with in that performance will degrade as the number increases (which might reflect the same coordination overhead issues in human interactions). 

To provide good overall performance for the user, therefore, we are looking at:
- Ways to make Syndi highly intuitive and efficient through gathering data on "successful" interactions with a user as well as what can possibly go wrong. 
- Understanding what specialist/collaborative agents should be connected to Syndi's for the most effective experience.

### Arachne Agent

A prototype agent focused on biomedical data fabric capabilities (data and knowledge engineering).

### Extraction Agent

The extraction agent can be run independently (terminal only) using `lein run -m agents.extraction`.

#### Vanilla Agent

This agent has no specific system prompt or tool access. It can be used as a "control" for testing and development. 

#### How to interface with an agent

##### Interactive chat

This is the most common AI-assisted workflow, where you choose a specific agent to be your assistant in an interactive session.

With the built-in web app client, you can open the web app with Syndi as the default agent (currently, this cannot be changed).

With the terminal, more agents can be accessed. The command is `clj -M -m agents.syndi` or `clj -M -m agents.arachne`.

##### Non-interactive batch/simulation

In non-interactive batch/simulation mode, one can create an agent and assign some task for the agent to figure out as one walks away and gets some coffee (akin to "deep research"). 
Currently, this is a more technical and manual mode to set up and **requires that you know what you're doing**, as it is especially like writing tests. 
It may also be tricky to simulate some tasks/interactions well. 

You may need to analyze what functions you need to replace so that agent actions don't affect production data, then set up an an environment with replacement of those functions. Example:
- The task is to review many records and commit a correction to the database (Synapse platform) as needed.
In an interactive version, you would have the chance to review and approve a correction before it is committed.
In the non-interactive simulated version, you replace the `commit` tool with a version that writes the updated record to a file instead, instead of actually committing to Synapse.
The result is a batch of files that you are able to review once the job is finished; you do not interact with the agent while it does its work. 


