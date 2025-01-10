### Syndi (Synapse Agent)

Syndi is the principal agent that can work with a predefined set of agents (at the moment, Syndi cannot be connected to a new agent dynamically).
"Connecting" Syndi to a new agent is a matter of adding the interface to another agent. 
While Syndi is an OpenAI agent, other specialist agents may be trained from another commercial AI Provider (e.g. Anthropic), a custom-architecture local AI model, or even a real person through a proxy agent; at the end of the day, an agent is just a nice abstraction and the main thing is that Syndi can interface with one. 
It is expected that there *are* limits on the number of agents that Syndi can interface with in that performance will degrade as the number increases (which might reflect the same coordination overhead issues in human interactions). 

To improve the overall performance of the application for the user, therefore, we would want:
- Having specialist agents at Syndi's disposal that are optimized (e.g. in terms of accuracy and cost-effectiveness). Occasionally we may reimplement an agent or replace it with a better version provided by someone else. 
- Syndi should be fine-tuned to be highly intuitive and make the best decisions for common workflows. Examples are understanding the user's intent, deciding when and which other agent should be called, how to interpret errors sensibly, etc. This is the difference between having a smart but new employee doing their job with a bit of guidance (prompts) vs having a smart employee who's been doing the job for years. Currently, Syndi is *not* fine-tuned as we need to collect data on "successful" interactions with a user as well as what can possibly go wrong. 

It is easy for other agents can be consulted and tested independently of Syndi, as their own modules.

### Extraction Agent

TBD

