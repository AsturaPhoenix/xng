# Executable Neural Graph

A prototype graph-based general purpose intelligence.

## Overview

At their core, both natural and artificial computational systems are causal chains. The executable neural graph is a proposed paradigm that seeks to support an adaptive, Turing-complete system of causal chains in a way that minimally constrains the resulting network topology and preserves plasticity while remaining computationally feasible.

The class of problem where this architecture is expected to excel over matrix methods is open domain online error correction, e.g. defining a new term or phrase in an NLU task, or interactive disambiguation during end-user programming.

## Mechanics

To keep computation feasible while not constraining network fanout, binary rather than continuous activation is used. This mimics biological systems but diverges from state-of-the-art artificial systems. While this means that the resulting model is not differentiable and gradient descent does not apply, this allows most paths of evaluation to drop out in any given computation. Meanwhile the probability distributions are shifted to synapse activation profiles (effectively stochastic parameters) so that repeated sampling and reinforcement should still enable training. Furthermore this allows for cyclic network topologies that would not preserve Bayesian semantics.

The current prototype capitalizes on the programmability of this architecture and hybridizes with scripting characteristics such as dictionary storage, stack frames, and calling convention. It is unclear whether the resulting reduction in generality/inferential power is significant, but at the very least this should serve as a platform capable of implementing a fully extensible, potentially ambiguous programming language.

## More details

Further documentation is in the [doc](doc) directory.

## Developing

### Visual Studio Code
To develop in VS Code,
1. Clone the git repo.
1. Open the cloned repo in VS Code (installing the Java extensions if required).
1. Download [Lombok](https://projectlombok.org/download).
1. [Configure](https://github.com/redhat-developer/vscode-java/wiki/Lombok-support)
   VS Code for Lombok.

### Eclipse
To develop in Eclipse,
1. [Lombok](https://projectlombok.org/download.html) must be installed.

Then

2. Clone the git repo.
3. Import the cloned repo in Eclipse as a Maven project.

or

2. Import the project in Eclipse from git as a Java project with the name "ekg".
3. Right click project/Configure/Convert to Maven project.
