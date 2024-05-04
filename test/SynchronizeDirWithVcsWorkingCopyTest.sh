#!/usr/bin/env bash

# Set a sane/secure path
PATH='/usr/local/bin:/bin:/usr/bin'
# It's almost certainly already marked for export, but make sure
\export PATH

# Clear all aliases. Important: leading \ inhibits alias expansion.
\unalias -a

# Clear the command path hash
hash -r

# Set the core dump limit to 0
ulimit -S -c 0 --

# Set a sane/secure IFS (note this is bash & ksh93 syntax only--not portable!)
IFS=$' \t\n'

# Set a sane/secure umask variable and use it
# Note this does not affect files already redirected on the command line
# 022 results in 0755 perms, 077 results in 0700 perms, etc.
UMASK=027
umask $UMASK

# Expand patterns, which match no files, to a null string, rather than themselves
shopt -s nullglob

# color code definitions:
declare -A colors
colors[gray]='\e[90m'
colors[red]='\e[91m'
colors[green]='\e[92m'
colors[orange]='\e[33m'
colors[blue]='\e[94m'
colors[magenta]='\e[95m'
colors[cyan]='\e[96m'
colors[reset]='\e[0m'

# Usage: color [-n] <color-code> arg ...
# Echo arguments in a given color
# -n works like for echo
function color
{
	newline=true
	if [ "$1" = '-n' ] ; then
		newline=false
		shift
	fi
	echo -ne "${colors[$1]}"
	shift
	echo -ne "$@${colors[reset]}"
	if $newline ; then
		echo
	fi
}

# Check availability of command »$1«.
# Optionally retrieve version of this command by either:
# parameters to the command »$1« given as »$2..$n« or:
# a shell function in »$2«.
function check_cmd
{
	if [ $# -ge 1 ] ; then
		if [ "`type -t -- $1`" = 'builtin' ] ; then
			color -n gray '· Using builtin' ; color orange " $1"
		else
			cmd="`type -p $1`"
			cmd_ver=''
			if [ -n "$cmd" ] ; then
				if [ $# -ge 2 ] ; then
					shift
					if [ "`type -t -- $1`" = 'function' ] ; then
						cmd_ver=" (`$@`)"
					else
						cmd_ver=" (`$cmd $@`)"
					fi
				fi
				color -n gray '· Using' ; echo -n " ${cmd}" ; color gray "${cmd_ver}"
			else
				echo "»${1}« not found! Please install first. Stop."
				exit 254
			fi
		fi
	else
		echo "Warning: function »check_cmd«: you gave me nothing to check, ignoring…"
	fi
}

declare -r test_cmd=SynchronizeDirWithVcsWorkingCopy
declare -r base_dir=`realpath $(dirname $0)/..`
declare -r vcs_cmd="${base_dir}/bin/${test_cmd}"

# check availability of commands needed by the script:

function check_necessary_commands
{
	if [ "${vcs}" = 'svn' ] ; then
		check_cmd svnadmin
		check_cmd svn --version --quiet
	elif [ "${vcs}" = 'git' ] ; then
		check_cmd git --version
	fi
	check_cmd diff
	check_cmd dirname
	check_cmd getopts
	check_cmd jar
	check_cmd java
	check_cmd realpath
	check_cmd tar
	check_cmd touch
	check_cmd ${vcs_cmd}
}

function show_usage
{
	color -n orange 'USAGE: '
	color gray "`basename $0` [options]"
	echo '       Run the unit tests for '
	color blue "       ${test_cmd}"
	echo
	color orange 'OPTIONS:'
	echo
	color -n blue '-h : ' ; echo 'show this help message'
	color -n blue '-r : ' ; echo 'run the unit tests for the selected VCS'
	color -n blue '-g : ' ; echo 'select git as VCS'
	color -n blue '-s : ' ; echo 'select subversion as VCS'
	color -n blue '-k : ' ; echo 'keep temporary test files'
	color -n blue '-v : ' ; echo 'be verbose'
	color -n blue '-V : ' ; echo 'show script version and exit'
}

function show_version
{
	version='1.0'
	if $be_verbose ; then
		echo "`basename $0` ${version}"
	else
		echo "$version"
	fi
	exit
}

function run_unit_tests
{
	if [ -z "${vcs}" ] ; then
		color red "Please select one of the VCS! Stop."
		exit 90
	fi
	if [ "${vcs}" = 'svn' ] ; then
		run_unit_tests_svn
	elif [ "${vcs}" = 'git' ] ; then
		run_unit_tests_git
	fi
}

declare keep=false

function create_temp_dir
{
	until [ -n "$temp_dir" -a ! -d "$temp_dir" ]; do
			temp_dir="${TMP:=/tmp}/${USER}/${test_cmd}.${RANDOM}${RANDOM}${RANDOM}"
	done
	mkdir -p -m 0700 $temp_dir \
		|| { echo "FATAL: Failed to create temp dir '$temp_dir': $?" ; exit 100 ; }

	if ! $keep ; then
		# Do our best to clean up temp files no matter what
		# Note $temp_dir must be set before this, and must not change!
		cleanup="rm -rf $temp_dir"
		trap "$cleanup" ABRT EXIT HUP INT QUIT
	fi
}

function run_unit_tests_svn
{
	jar1=`realpath "${base_dir}/test/resources/JX3fExtract-2.1-javadoc.jar"`
	jar2=`realpath "${base_dir}/test/resources/JX3fExtract-2.2-javadoc.jar"`

	create_temp_dir

	color blue "· temp dir »${temp_dir}«"
	color blue "· base dir »${base_dir}«"
	color blue "· command  »${vcs_cmd}«"
	color blue "· jar1     »${jar1}«"
	color blue "· jar2     »${jar2}«"

	pushd "${temp_dir}"
	mkdir -vp src{1..2} exported{1..2} svn-repos working-copy

	if [ ! -x "${vcs_cmd}" ] ; then
		color red "Test command not found! Stop."
		exit 2
	fi

	if [ -f "${jar1}" ] ; then
		pushd src1
		jar xf "${jar1}"
		popd
	else
		color red "Test file »${jar1}« not found! Stop."
		exit 3
	fi

	if [ -f "${jar2}" ] ; then
		pushd src2
		jar xf "${jar2}"
		popd
	else
		color red "Test file »${jar2}« not found! Stop."
		exit 4
	fi

	if ! "${vcs_cmd}" --help ; then
		color red "TEST FAIL : running usage help"
		exit 11
	fi

	if "${vcs_cmd}" --invalid-option ; then
		color red "TEST FAIL : invalid option"
		exit 12
	fi

	pushd svn-repos
	if ! svnadmin create testrepo ; then
		color red "Can't initialize subversion repository! Stop."
		exit 13
	fi
	url_repo="file://${temp_dir}/svn-repos/testrepo"
	popd
	pushd working-copy
	if svn checkout "${url_repo}" ; then
		cd testrepo
	else
		color red "Can't checkout subversion repository! Stop."
		exit 19
	fi

	svn mkdir --parents a/b
	touch a/b/testfile
	if ! svn add a/b/testfile ; then
		color red "Can't add test file! Stop."
		exit 14
	fi
	if ! svn commit -m 'Test commit #001' ; then
		color red "Can't commit test file! Stop."
		exit 15
	fi

	color blue "DIR TREE TEST #1"

	${vcs_cmd} --svn '../../src1/JX3fExtract-2.1-javadoc' .
	if ! svn commit -m 'Test commit #002' ; then
		color red "Can't commit test dir! Stop."
		exit 16
	fi
	pushd '../../exported1/'
	svn export "${url_repo}"
	popd
	if diff --recursive '../../src1/JX3fExtract-2.1-javadoc' '../../exported1/testrepo' ; then
		color green "TEST #1 SUCCESS : src and repo dirs are equal :)"
	else
		color red "TEST #1 FAIL : src and repo dirs differ! Stop."
		exit 21
	fi

	color blue "DIR TREE TEST #2"

	${vcs_cmd} --svn '../../src2/JX3fExtract-2.2-javadoc' .
	if ! svn commit -m 'Test commit #003' ; then
		color red "Can't commit test dir! Stop."
		exit 17
	fi
	pushd '../../exported2/'
	svn export "${url_repo}"
	popd
	if diff --recursive '../../src2/JX3fExtract-2.2-javadoc' '../../exported2/testrepo' ; then
		color green "TEST #2 SUCCESS : src and repo dirs are equal :)"
	else
		color red "TEST #2 FAIL : src and repo dirs differ! Stop."
		exit 22
	fi
}

function git_export()
{
	git archive --format=tar HEAD | tar x -C "$1"
}

function run_unit_tests_git
{
	jar1=`realpath "${base_dir}/test/resources/JX3fExtract-2.1-javadoc.jar"`
	jar2=`realpath "${base_dir}/test/resources/JX3fExtract-2.2-javadoc.jar"`

	create_temp_dir

	color blue "· temp dir »${temp_dir}«"
	color blue "· base dir »${base_dir}«"
	color blue "· command  »${vcs_cmd}«"
	color blue "· jar1     »${jar1}«"
	color blue "· jar2     »${jar2}«"

	pushd "${temp_dir}"
	mkdir -vp src{1..2} exported{1..2} git-repos

	if [ ! -x "${vcs_cmd}" ] ; then
		color red "Test command not found! Stop."
		exit 2
	fi

	if [ -f "${jar1}" ] ; then
		pushd src1
		jar xf "${jar1}"
		popd
	else
		color red "Test file »${jar1}« not found! Stop."
		exit 3
	fi

	if [ -f "${jar2}" ] ; then
		pushd src2
		jar xf "${jar2}"
		popd
	else
		color red "Test file »${jar2}« not found! Stop."
		exit 4
	fi

	if ! "${vcs_cmd}" --help ; then
		color red "TEST FAIL : running usage help"
		exit 11
	fi

	if "${vcs_cmd}" --invalid-option ; then
		color red "TEST FAIL : invalid option"
		exit 12
	fi

	pushd git-repos
	if ! git init ; then
		color red "Can't initialize git repository! Stop."
		exit 13
	fi

	mkdir -vp a/b
	touch a/b/testfile
	if ! git add a/b/testfile ; then
		color red "Can't add test file! Stop."
		exit 14
	fi
	if ! git commit -m 'Test commit #001' ; then
		color red "Can't commit test file! Stop."
		exit 15
	fi

	color blue "DIR TREE TEST #1"

	${vcs_cmd} --git '../src1/JX3fExtract-2.1-javadoc' .
	if ! git commit -m 'Test commit #002' ; then
		color red "Can't commit test dir! Stop."
		exit 16
	fi
	git_export '../exported1/'
	if diff --recursive '../src1/JX3fExtract-2.1-javadoc' '../exported1' ; then
		color green "TEST #1 SUCCESS : src and repo dirs are equal :)"
	else
		color red "TEST #1 FAIL : src and repo dirs differ! Stop."
		exit 21
	fi

	color blue "DIR TREE TEST #2"

	${vcs_cmd} --git '../src2/JX3fExtract-2.2-javadoc' .
	if ! git commit -m 'Test commit #003' ; then
		color red "Can't commit test dir! Stop."
		exit 17
	fi
	git_export '../exported2/'
	if diff --recursive '../src2/JX3fExtract-2.2-javadoc' '../exported2' ; then
		color green "TEST #2 SUCCESS : src and repo dirs are equal :)"
	else
		color red "TEST #2 FAIL : src and repo dirs differ! Stop."
		exit 22
	fi
}

# global verbosity level:
declare be_verbose=false
declare -i verbosity_level=0

# script specific action:
declare action
declare vcs

while getopts 'hrgskvV' arg
do
	case "$arg" in
		h)
			action=show_usage ;;
		r)
			action=run_unit_tests ;;
		g)
			vcs=git ;;
		s)
			vcs=svn ;;
		k)
			keep=true ;;
		v)
			be_verbose=true ; ((verbosity_level++)) ;;
		V)
			action=show_version ;;
		?)
			action=show_usage ; exit 98 ;;
	esac
done
shift $(($OPTIND - 1))

# run the script specific action:

if [ -n "$action" ] ; then
	if $be_verbose ; then
		check_necessary_commands
	fi
	$action
else
	show_usage
	exit 99
fi

