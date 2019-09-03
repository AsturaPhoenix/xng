# Documentation

## Application

This AI operates as an adaptive program. Rather than bootstrapping it with structure and training data, one needs to bootstrap it with a base program. For a language-based approach to intelligence, for example, it is reasonable that this base program should be a form of sliding window n-gram interpreter in something resembling a REPL, keeping the sequence recognition facility factored appropriately so that the program can reuse it to recognize sequences at multiple levels. It would then also be helpful to include explicit graph modification and reinforcement commands (e.g. a basic DSL). Thereafter further adaptation or explicit programming through the parser constitutes a form of online learning.

Provided the architecture itself demonstrates reasonable reinforcement behavior (e.g. classical conditioning), the problem space of designing the bootstrap program is itself an interesting one, and the above proposal is only an example approach. This example should allow it to achieve the milestone of being an extensible programming language, from which the next challenge would be to extend it to the ambiguity of natural language. Many further facilities would be needed to bridge the gap, but these facilities should themselves be encodable into the adaptive program space.

## Early limitations

It is well known that biological neural systems do segregate and specialize, so it is likely that at some point these mechanics will need to be incorporated. Furthermore the proof of concept does not model different neuromodulators.
