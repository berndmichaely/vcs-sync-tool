# 2. sync-vcs-tool

Date: 2021-02-05

## Status

Accepted

## Context

There are situations, where (changes to) auto created content is to be committed to a version control system (VCS).

While there are numerous tools providing a GUI, it is sometimes desirable to have a command which can be executed on a command line or from whithin a deployment script. (As an example, think of generated javadoc to be published on a website.)

## Decision

We provide a simple script in form of a Java shebang script. The script will be based on system commands (as opposed to library calls). We will support the VCS _Subversion_ and _Git_.

## Consequences

We will hava available a simple script to apply changes of auto generated content to directory trees under version control.

