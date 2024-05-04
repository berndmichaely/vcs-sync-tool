# vcs-sync-tool

![Version 1.2](./doc/shields/vcs-sync-tool_1.2.svg "Version 1.2")

`SynchronizeDirWithVcsWorkingCopy` is a Java shebang script to synchronize a directory tree, which is under version control, with a new target state. Supported VCS are Subversion and Git.

The `<destination-directory>` contains a content under version control, and the `<source-directory>` contains a new version of the destination content. Empty source directories are ignored. The script will update the `<destination-directory>` content using version control commands.

The `test` subdirectory contains unit tests as a linux bash script.

## Usage:

    SynchronizeDirWithVcsWorkingCopy [ -d | -s | -g ] <source-directory> <destination-directory>
    Replicate the state of a source directory tree to a target directory which is under version control.
    -h | --help             : show this help
    -d | --dry-run          : dry run, perform no action, just show info
    -s | --svn              : run SVN commands
    -g | --git              : run GIT commands
    -                       : stop parsing options
    <source-directory>      : new content
    <destination-directory> : existing VCS working copy

