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
