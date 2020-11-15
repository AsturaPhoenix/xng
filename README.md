# Executable Neural Graph

A prototype exploring a fully extensible scripting language built into a simplified spiking neural network, with the goal of strong natural language understanding.

## Overview

At their core, both natural and artificial computational systems are causal chains. The executable neural graph is a paradigm that seeks to support an adaptive, Turing-complete system of causal chains in a way that minimally constrains the resulting network topology and preserves plasticity while remaining computationally feasible.

The class of problem where this architecture is expected to excel over matrix methods is open domain online error correction, e.g. defining a new term or phrase in an NLU task, or interactive disambiguation during end-user programming.

Further documentation is in the [wiki](https://github.com/AsturaPhoenix/xng/wiki).

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

2. Import the project in Eclipse from git as a Java project with the name "xng".
3. Right click project/Configure/Convert to Maven project.
