# vcs-sync-tool

![Version 1.3](./doc/shields/vcs-sync-tool.svg "Version 1.3")

`SynchronizeDirWithVcsWorkingCopy` is a Java shebang script to synchronize a directory tree, which is under version control, with a new target state. Supported VCS are Subversion and Git.

The `<destination-directory>` contains a content under version control, and the `<source-directory>` contains a new version of the content. The script will update the `<destination-directory>` content from the `<source-directory>` using version control commands. Empty source directories are ignored.

The `test` subdirectory contains unit tests as a bash script.

## Usage:

To display some usage help, run:

`> SynchronizeDirWithVcsWorkingCopy -h`

which will show:

    usage : SynchronizeDirWithVcsWorkingCopy [ -v ] < -d | -s | -g > <source-directory> <destination-directory>
            Replicate the state of a source directory tree to a target directory which is under version control.

            -d | --dry-run          : dry run, perform no action, just show info
            -g | --git              : run GIT commands
            -s | --svn              : run SVN commands
            -v | --verbose          : show more verbose output (e.g. list unmodified files)

            -h | --help             : show this help
            -V | --version          : show version and exit
            -                       : stop parsing options
            <source-directory>      : new content
            <destination-directory> : existing VCS working copy
