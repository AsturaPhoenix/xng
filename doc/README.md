# Documentation

## Mechanics

To keep computation feasible while not constraining network fanout, we use a simplified [spiking neural network](https://en.wikipedia.org/wiki/Spiking_neural_network) (SNN) rather than matrices. While this means that the resulting model is not differentiable and gradient descent does not apply, this allows most paths of evaluation to drop out in any given computation. Furthermore this maps well to executable semantics, and allows for recurrent network topologies that would not preserve Bayesian semantics.

### Nodes

Nodes are the atom of state and network structure, and are basically neurons. Nodes may allow presynaptic connections, postsynaptic connections, or both. Input nodes may be activated by the system, and action nodes can trigger built-in side effects.

For now, we shall refer to nodes that can be presynaptic as priors and nodes that can be postsynaptic as posteriors.

### Clusters

Clusters are collections of nodes that constrain overarching connection topologies and allow ensemble operations such as association and reinforcement. Special cluster implementations may have additional behaviors.

This is an area under active development. For additional details of dubious veracity, please refer to the [Inter-cluster relations for fun and profit](https://docs.google.com/document/d/1s6RjSBI8i2uzhhD8nv96QJy2JVDaByFwyLFHbg01vdA) design dump.

### Synapses

Synapses represent the junction between nodes. The synapses used are a heavily simplified [leaky integrate and fire](https://en.wikipedia.org/wiki/Biological_neuron_model) model with linear combination and linear decay in long- and short-decay timing profiles.

At a high level, the SNN operates as a time-continuous mapping from states to actions. The line between states and actions is blurred, as nodes towards built-in actions are themselves atoms of state. The states thus considered can be described as abstract aggregates of recency-weighted node activations.

Biologically, several more complex mechanisms should be at work, which are omitted from the prototype in the short term. In particular, the simplified synapse model does not exhibit [adaptation or vesicle depletion](https://en.wikipedia.org/wiki/Synaptic_fatigue), and decay is linear.

### Distributions

The coefficients governing the linear combination of incoming nodes in synapses are modeled by distribution data structures.

The current implementation is unimodal, evidence-based, and deterministic. Each instance can be roughly characterized by an expected value (coefficient) and a weight (resilience), with additional state and logic to tune behavior when feedback is received that suggests the generated value should have been different.

Feedback to the distribution comes in the form of [STDP](https://en.wikipedia.org/wiki/Spike-timing-dependent_plasticity), reward/penalty-based reinforcement, and explicit manipulation by node actions (e.g. binding, unbinding, and scaling).

An earlier implementation included random perturbation. However, system behavior at the time suggested that merely incorporating causal feedback in conjunction with race conditions seemed sufficient to guide convergence via reinforcement. The architecture has changed drastically since then, so it is unclear whether this will continue to be sufficient.

## Bootstrapping

This network needs to be bootstrapped with a parser for a minimal extensible language. To accomplish this, there are several mechanisms under development.

### Interop value

To facilitate interop with built-in logic, a special `DataCluster` contains posteriors that hold Java values. These values are published to an event stream when their node is activated.

Nodes values may be mutable or final. Mutable nodes additionally trigger a dedicated node when they are assigned to.

### Context binding

Context binding is a mechanism that helps us bind priors to a cluster that can also effectively be posteriors of that cluster in a way that allows us to refer to those posteriors without undue risk of spuriously activating further nodes predicated on the context. To accomplish this, we introduce the `GatedBiCluster`, which effectively acts as an input cluster connected to an output cluster with the output predicated on a gating node. While probably not strictly required, this cluster allows us to easily and consistently implement such a mechanism without repeatedly worrying about setting up the connections.

In addition to context, this mechanism can be used for property bindings ([entity-attribute-value](https://en.wikipedia.org/wiki/Entity%E2%80%93attribute%E2%80%93value_model)), dictionary storage, and stack frames.

### Stack frames

Stack frames are a special application of context binding where context nodes are "pushed onto a stack" by way of binding them to a stack node while scaling down any existing posteriors of that node. They are popped by activating the stack node and dissassociating the resulting posteriors, and then scaling up any remaining posteriors.

Stack frames may additionally serve in other capacities as representations of a task, such as in parsing, planning, and episodic memory.

For more up-to-date (or, someday, less obsolete) rambling, see the [Ramblings of a neuromorphic computer](https://docs.google.com/document/d/1jHAo-Uo5EnDO5k8T_TrSVvyQSk-Q-9QjJeqxzC06HqU) design dump.

### Calling convention

Unlike more classical calling conventions, there is no intrinsic handling of a return value in the stack frames described above. Instead, we can push interstitial tasks onto the stack that bind or copy results from dependency tasks into their dependent tasks.

In addition to binding arguments to stack frames, which can be seen as named arguments, we can also allow sequential activations to represent positional arguments. This is a straightforward way to implement interop, as built-ins can simply listen to the data cluster for arguments. This is less straightforward for node-space calls. One possibility is to bind positional arguments on a timed cadence.

### Decoders and other constructs

Decoders are domain-specific built-in boosts that can be activated on data to decode into more meaningful node ensembles. A simple example of this is a Boolean decoder, which simply activates a different node depending on whether a value is true. Other decoders in use are binary decoders and character class decoders.

Another construct that may be of value is the latch. Latches can be implemented in node space using timing loops but are thus not as simple nor as robust as they could be (although this may be indicative of missing design requirements).

## Relation to prior art

The area of focus this paradigm attempts to exploit is the adaptation of a self-modifying, Turing-complete program.

Binary and topologically unconstrained networks have been investigated in the past, but as far as I can tell they have attempted to operate on pure convergence ([attractor networks](https://en.wikipedia.org/wiki/Attractor_network)), with constraints imposed upon global heuristics. A large body of more recent research (including deep learning) has focused instead on function approximation.

There is also a body of recent work dedicated to [program induction](https://www.sciencedirect.com/science/article/pii/S1364661320301741), which I am only now beginning to digest. It is likely that the exploration and program induction techniques investigated in this area will need to be incorporated into the adaptive program here in order to allow it to explore and evolve efficiently on its own.

The ideas around extensible languages were explored in an earlier exploratory project, [Alchemy](https://github.com/AsturaPhoenix/alchemy). That project was shelved as it became apparent that an extensible rewrite parser may share some characteristics with semantic networks and led to this project.

### Design dumps

The docs that follow are the unfiltered design dumps in use during development, from newest to oldest. The material in these documents is unlikely to be correct and should not be regarded as documentation.

* [Ramblings of a neuromorphic computer](https://docs.google.com/document/d/1jHAo-Uo5EnDO5k8T_TrSVvyQSk-Q-9QjJeqxzC06HqU)
* [Inter-cluster relations for fun and profit](https://docs.google.com/document/d/1s6RjSBI8i2uzhhD8nv96QJy2JVDaByFwyLFHbg01vdA)
* [Late game via imitation](https://docs.google.com/document/d/1yLwS7jc68y2iI_YOqapI7gwf_fuKhTe4vxJp2Ziy75E)
* [Late game roadmap](https://docs.google.com/document/d/122iv-1Sx2BqxkITps-iJpxSkQ6lsCxFzT50aG5XXYeU)
* [Midgame roadmap](https://docs.google.com/document/d/1dVCerydJxlQFUe0xZIz1jw1TG3yIOUeB7sQNRfcp2aE)
* [Sequence recognition](https://docs.google.com/document/d/1Me6UJyLyN0lRo7mu4hyX_Ed7etCOWne0rCCCICkiLWE)
* [Reinforcement](https://docs.google.com/document/d/1qh-pqyo6LSs3v5Wkp7iopMOahEXSTnDw6eglG5QYeyI)
* [Return semantics](https://docs.google.com/document/d/1U33hYAovcBOEtXT3TJOVQVr8OlJJkWpQPzqIL3nnsWA)
