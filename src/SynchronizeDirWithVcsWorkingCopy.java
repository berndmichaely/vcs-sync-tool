//#!/usr/bin/java --source 17

/*
 * Copyright 2024 Bernd Michaely (info@bernd-michaely.de).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package shebang.netbeans;
// last updated: 2024-05-04

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;

import static java.nio.file.FileVisitResult.*;
import static java.nio.file.StandardCopyOption.*;
import static java.util.function.Predicate.not;

/**
 * Java shebang script to replicate the state of a source directory tree to a
 * target directory which is under version control. Supported version control
 * systems are subversion and git.
 *
 * @author Bernd Michaely (info@bernd-michaely.de)
 */
public class SynchronizeDirWithVcsWorkingCopy
{
  private Path baseDirSrc, baseDirDst;
  private Action action;
  private final List<SystemCommand> systemCommands = new ArrayList<>();
  private boolean validPaths;
  private final SortedSet<Path> srcFiles = new TreeSet<>();
  private final SortedSet<Path> srcDirectories = new TreeSet<>();
  private final SortedSet<Path> dstFiles = new TreeSet<>();
  private final SortedSet<Path> dstDirectories = new TreeSet<>();

  private enum Action
  {
    SHOW_HELP, DRY_RUN, SVN, GIT
  }

  private enum Op
  {
    MKDIR, ADD, MOD, DEL, RMDIR
  }

  /**
   * Interface to describe the elementary actions to perform.
   */
  private interface SystemCommand
  {
    /**
     * Add a dir, which is present in the src, but not in the dst.
     *
     * @param subPath the relative dir path to add
     * @throws ScriptException if an {@link IOException} occurs or running a
     *                         system command fails
     */
    void addDir(Path subPath);

    /**
     * Add a file, which is present in the src, but not in the dst.
     *
     * @param subPath the relative file path to add
     * @throws ScriptException if an {@link IOException} occurs or running a
     *                         system command fails
     */
    void addFile(Path subPath);

    /**
     * Apply changes to a file, which is present in src and dst.
     *
     * @param subPath the relative file path to change
     * @throws ScriptException if an {@link IOException} occurs or running a
     *                         system command fails
     */
    void changeFile(Path subPath);

    /**
     * Remove a file, which is present in the dst, but not in the src.
     *
     * @param subPath the relative file path to remove
     * @throws ScriptException if an {@link IOException} occurs or running a
     *                         system command fails
     */
    void removeFile(Path subPath);

    /**
     * Remove a directory, which is present in the dst, but not in the src.
     *
     * @param subPath the relative directory path to remove
     * @throws ScriptException if an {@link IOException} occurs or running a
     *                         system command fails
     */
    void removeDir(Path subPath);
  }

  /**
   * Implementation for logging purposes.
   */
  private class SystemCommandLog implements SystemCommand
  {
    private void logPath(String prefix, Path path, AnsiColorEscapeCodes color)
    {
      System.out.println(formatAsColored(prefix, color) + path);
    }

    @Override
    public void addDir(Path subPath)
    {
      logPath(Op.MKDIR + " : ", subPath, AnsiColorEscapeCodes.CYAN);
    }

    @Override
    public void addFile(Path subPath)
    {
      logPath(Op.ADD + "   : ", subPath, AnsiColorEscapeCodes.GREEN);
    }

    @Override
    public void changeFile(Path subPath)
    {
      logPath(Op.MOD + "   : ", subPath, AnsiColorEscapeCodes.BLUE);
    }

    @Override
    public void removeFile(Path subPath)
    {
      logPath(Op.DEL + "   : ", subPath, AnsiColorEscapeCodes.RED);
    }

    @Override
    public void removeDir(Path subPath)
    {
      logPath(Op.RMDIR + " : ", subPath, AnsiColorEscapeCodes.MAGENTA);
    }
  }

  /**
   * Base class for VCS related implementations containing utility methods for
   * file operations and for running system commands.
   */
  private abstract class SystemCommandVcs implements SystemCommand
  {
    /**
     * Runs a system command. The commands std output goes to stdout. The
     * commands err output goes to stderr.
     *
     * @param args the command line to run in the system
     * @throws ScriptException if the command returns an error status. The
     *                         exception message includes the returned error
     *                         code.
     */
    protected void runCommand(String... args)
    {
      final int result;
      try
      {
        result = new ProcessBuilder(args).directory(baseDirDst.toFile()).inheritIO().start().waitFor();
      }
      catch (IOException | InterruptedException ex)
      {
        throw new ScriptException(11, ex);
      }
      if (result != 0)
      {
        throw new ScriptException(12,
          "Error code »" + result + "« returned by system command : " + List.of(args));
      }
    }

    /**
     * Runs a system command and returns its output. The commands err output
     * goes to stderr.
     *
     * @param args the command line to run in the system
     * @return the command output
     * @throws ScriptException if the command returns an error status. The
     *                         exception message includes the returned error
     *                         code.
     */
    protected String readCommandOutput(String... args)
    {
      try
      {
        final ProcessBuilder builder = new ProcessBuilder(args).directory(baseDirDst.toFile());
        final Process process = builder.start();
        process.getErrorStream().transferTo(System.err);
        try (var s = new ByteArrayOutputStream())
        {
          final int result = process.waitFor();
          if (result != 0)
          {
            throw new ScriptException(13,
              "Error code »" + result + "« returned by system command : " + List.of(args));
          }
          process.getInputStream().transferTo(s);
          return s.toString("UTF-8");
        }
      }
      catch (IOException | InterruptedException ex)
      {
        throw new ScriptException(14, ex);
      }
    }

    /**
     * Checks, whether the given dir or one of its ancestors has a given subdir.
     *
     * @param dir    the dir and its parent directories to be checked
     * @param subDir the subdirectory to search for
     * @return true, iff the given dir or one of its ancestors has the given
     *         subdir
     */
    private boolean hasParentWithSubDir(Path dir, String subDir)
    {
      Path p = dir;
      while (p != null)
      {
        if (Files.isDirectory(p.resolve(subDir)))
        {
          return true;
        }
        p = p.getParent();
      }
      return false;
    }

    /**
     * Returns the VCS specific working copy subdirectory.
     *
     * @return the VCS specific working copy subdirectory (e.g.
     *         <code>".svn"</code> or <code>".git"</code>)
     */
    protected abstract String getVcsDirName();

    /**
     * Checks, whether the destination directory is under version control.
     *
     * @return true, iff the destination directory is under version control
     */
    protected boolean isDstDirUnderVersionControl()
    {
      return hasParentWithSubDir(baseDirDst, getVcsDirName());
    }

    /**
     * Returns true, iff the VCS working copy is clean.
     *
     * @return true, iff the VCS working copy is clean
     * @throws ScriptException if the check fails
     */
    protected abstract boolean isWorkingCopyClean();

    @Override
    public void addFile(Path subPath)
    {
      try
      {
        Files.copy(baseDirSrc.resolve(subPath), baseDirDst.resolve(subPath), COPY_ATTRIBUTES);
      }
      catch (IOException ex)
      {
        throw new ScriptException(21, ex);
      }
    }

    @Override
    public void changeFile(Path subPath)
    {
      try
      {
        Files.copy(baseDirSrc.resolve(subPath), baseDirDst.resolve(subPath), COPY_ATTRIBUTES, REPLACE_EXISTING);
      }
      catch (IOException ex)
      {
        throw new ScriptException(22, ex);
      }
    }
  }

  /**
   * Implementation for the subversion VCS.
   */
  private class SystemCommandSvn extends SystemCommandVcs
  {
    private static final String DIR_NAME_SVN = ".svn";

    @Override
    protected String getVcsDirName()
    {
      return DIR_NAME_SVN;
    }

    @Override
    protected boolean isWorkingCopyClean()
    {
      return readCommandOutput("svn", "status").isBlank();
    }

    @Override
    public void addDir(Path subPath)
    {
      runCommand("svn", "mkdir", subPath.toString());
    }

    @Override
    public void addFile(Path subPath)
    {
      super.addFile(subPath);
      runCommand("svn", "add", subPath.toString());
    }

    @Override
    public void removeFile(Path subPath)
    {
      runCommand("svn", "remove", subPath.toString());
    }

    @Override
    public void removeDir(Path subPath)
    {
      runCommand("svn", "remove", subPath.toString());
    }
  }

  /**
   * Implementation for the git VCS.
   */
  private class SystemCommandGit extends SystemCommandVcs
  {
    private static final String DIR_NAME_GIT = ".git";

    @Override
    protected String getVcsDirName()
    {
      return DIR_NAME_GIT;
    }

    @Override
    protected boolean isWorkingCopyClean()
    {
      return readCommandOutput("git", "status", "--porcelain").isBlank();
    }

    @Override
    public void addDir(Path subPath)
    {
      try
      {
        Files.createDirectory(baseDirDst.resolve(subPath));
      }
      catch (IOException ex)
      {
        throw new ScriptException(23, ex);
      }
    }

    @Override
    public void addFile(Path subPath)
    {
      super.addFile(subPath);
      runCommand("git", "add", subPath.toString());
    }

    @Override
    public void changeFile(Path subPath)
    {
      super.changeFile(subPath);
      runCommand("git", "add", subPath.toString());
    }

    @Override
    public void removeFile(Path subPath)
    {
      runCommand("git", "rm", subPath.toString());
    }

    @Override
    public void removeDir(Path subPath)
    {
      // git cleans up empty directories itself
    }
  }

  /**
   * Enum to control colored out.
   */
  private enum AnsiColorEscapeCodes
  {
    BLACK, RED, GREEN, YELLOW, BLUE, MAGENTA, CYAN, WHITE;

    private int getFgCode(boolean bright)
    {
      return ordinal() + (bright ? 90 : 30);
    }

    private int getBgCode(boolean bright)
    {
      return ordinal() + (bright ? 100 : 40);
    }

    private static String formatAsColored(String s,
      AnsiColorEscapeCodes colorFg, AnsiColorEscapeCodes colorBg, boolean bright)
    {
      if (s == null)
      {
        return "";
      }
      if (colorFg == null && colorBg == null)
      {
        return s;
      }
      final String CSI = "\u001B[";
      final String FB = "m";
      final String RESET = CSI + 0 + FB;
      final StringBuilder b = new StringBuilder();
      if (colorFg != null)
      {
        b.append(CSI).append(colorFg.getFgCode(bright)).append(FB);
      }
      if (colorBg != null)
      {
        b.append(CSI).append(colorBg.getBgCode(bright)).append(FB);
      }
      b.append(s).append(RESET);
      return b.toString();
    }
  }

  private static final AnsiColorEscapeCodes COLOR_HEADER = AnsiColorEscapeCodes.YELLOW;

  /**
   * ScriptException class providing an error code to return to system.
   */
  public static class ScriptException extends RuntimeException
  {
    private final int errorCode;

    private ScriptException(int errorCode, String message)
    {
      super(message);
      this.errorCode = errorCode;
    }

    private ScriptException(int errorCode, Throwable cause)
    {
      super(cause);
      this.errorCode = errorCode;
    }

    private int getErrorCode()
    {
      return errorCode;
    }
  }

  /**
   * Returns a version of the given String suitable for colored printing.
   *
   * @param s       the given String
   * @param colorFg the foreground color
   * @return a version of the given String suitable for colored printing to
   *         System.out
   */
  private static String formatAsColored(String s, AnsiColorEscapeCodes colorFg)
  {
    return AnsiColorEscapeCodes.formatAsColored(s, colorFg, null, true);
  }

  public static void main(String[] args)
  {
    try
    {
      final var sync = new SynchronizeDirWithVcsWorkingCopy(args);
      if (sync.checkArgs())
      {
        sync.findPaths();
        sync.applyChanges();
      }
    }
    catch (ScriptException ex)
    {
      ex.printStackTrace();
      System.exit(ex.getErrorCode());
    }
    catch (Throwable ex)
    {
      ex.printStackTrace();
      System.exit(99);
    }
  }

  private SynchronizeDirWithVcsWorkingCopy(String... args)
  {
    parseArgs(new LinkedList<>(args != null ? List.of(args) : List.of()));
  }

  private void parseArgs(Queue<String> listArgs)
  {
    if (listArgs.isEmpty())
    {
      action = Action.SHOW_HELP;
    }
    else
    {
      String nextArg = listArgs.poll();
      while (nextArg != null && nextArg.startsWith("-") && !nextArg.equals("-"))
      {
        switch (nextArg)
        {
          case "-h", "--help" ->
            action = Action.SHOW_HELP;
          case "-d", "--dry-run" ->
            action = Action.DRY_RUN;
          case "-s", "--svn" ->
            action = Action.SVN;
          case "-g", "--git" ->
            action = Action.GIT;
          default ->
          {
            action = null;
            return;
          }
        }
        nextArg = listArgs.poll();
      }
      if ("-".equals(nextArg) && !listArgs.isEmpty())
      {
        nextArg = listArgs.poll();
      }
      if (nextArg != null && listArgs.size() <= 1)
      {
        final Path path1 = Paths.get(nextArg);
        baseDirSrc = Files.isDirectory(path1) ? path1.toAbsolutePath().normalize() : null;
        nextArg = listArgs.poll();
        if (nextArg != null)
        {
          final Path path2 = Paths.get(nextArg);
          baseDirDst = Files.isDirectory(path2) ? path2.toAbsolutePath().normalize() : null;
        }
        validPaths = baseDirSrc != null && baseDirDst != null;
      }
    }
  }

  private static void showUsage()
  {
    List.of(
      "usage : " + SynchronizeDirWithVcsWorkingCopy.class.getSimpleName() + " [ -d | -s | -g ] <source-directory> <destination-directory>",
      "        Replicate the state of a source directory tree to a target directory which is under version control.",
      "        -h | --help             : show this help",
      "        -d | --dry-run          : dry run, perform no action, just show info",
      "        -s | --svn              : run SVN commands",
      "        -g | --git              : run GIT commands",
      "        -                       : stop parsing options",
      "        <source-directory>      : new content",
      "        <destination-directory> : existing VCS working copy"
    )
      .forEach(System.out::println);
  }

  /**
   * Check the given command line arguments.
   *
   * @return true to continue running the script, false to show usage help or
   *         errors
   * @throws ScriptException if invalid arguments or directories are specified
   */
  private boolean checkArgs()
  {
    if (action == null)
    {
      showUsage();
      throw new ScriptException(1, "Invalid options specified.");
    }
    else if (action.equals(Action.SHOW_HELP))
    {
      showUsage();
      return false;
    }
    else if (!validPaths)
    {
      if (baseDirSrc == null)
      {
        throw new ScriptException(2, "Source directory is invalid!");
      }
      if (baseDirDst == null)
      {
        throw new ScriptException(3, "Destination directory is invalid!");
      }
    }
    return true;
  }

  /**
   * Collects paths for source and destination.
   *
   * @throws ScriptException if an {@link IOException} occurs or running a
   *                         system command fails
   */
  private void findPaths()
  {
    systemCommands.add(new SystemCommandLog());
    final String strMode;
    final SystemCommandVcs vcs;
    switch (action)
    {
      case DRY_RUN ->
      {
        strMode = "DRY-RUN";
        vcs = null;
      }
      case SVN ->
      {
        strMode = "RUN SVN";
        vcs = new SystemCommandSvn();
      }
      case GIT ->
      {
        strMode = "RUN GIT";
        vcs = new SystemCommandGit();
      }
      default ->
        throw new ScriptException(28, "Unknown action specified!");
    }
    if (vcs != null)
    {
      if (!vcs.isDstDirUnderVersionControl())
      {
        throw new ScriptException(24, "Destination directory is not under version control!");
      }
      if (!vcs.isWorkingCopyClean())
      {
        throw new ScriptException(25, "Working copy is not clean! Please commit or revert changes first.");
      }
      systemCommands.add(vcs);
    }
    System.out.println("Replicate data" + formatAsColored(" => " + strMode, COLOR_HEADER));
    System.out.println(formatAsColored("=> from source directory", COLOR_HEADER) + " »" + baseDirSrc + "«");
    System.out.println(formatAsColored("=> to   target directory", COLOR_HEADER) + " »" + baseDirDst + "«");
    scanDirectoryTree(baseDirSrc, srcDirectories, srcFiles, true);
    scanDirectoryTree(baseDirDst, dstDirectories, dstFiles, false);
  }

  private void scanDirectoryTree(Path startDir, SortedSet<Path> directories,
    SortedSet<Path> files, boolean excludeEmptyDirs)
  {
    /**
     * A FileVisitor to collect directory and file paths into given collections.
     * It takes care to ignore VCS directories (like <code>".svn"</code> or
     * <code>".git"</code>).
     */
    final FileVisitor<Path> fileVisitor = new SimpleFileVisitor<>()
    {
      private final Collection<Path> excludes = Set.of(
        startDir.resolve(SystemCommandSvn.DIR_NAME_SVN),
        startDir.resolve(SystemCommandGit.DIR_NAME_GIT));

      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
        throws IOException
      {
        if (dir.equals(startDir))
        {
          return CONTINUE;
        }
        else if (excludes.contains(dir))
        {
          return SKIP_SUBTREE;
        }
        else
        {
          directories.add(startDir.relativize(dir));
          return CONTINUE;
        }
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
        throws IOException
      {
        files.add(startDir.relativize(file));
        return CONTINUE;
      }
    };
    try
    {
      Files.walkFileTree(startDir, fileVisitor);
      if (excludeEmptyDirs)
      {
        final List<Path> emptySrcDirs = directories.stream()
          .filter(srcDir -> files.stream().noneMatch(file -> file.startsWith(srcDir)))
          .toList();
        emptySrcDirs.forEach(emptySrcDir ->
        {
          System.out.print(formatAsColored("=> Ignoring empty source directory :", COLOR_HEADER));
          System.out.println(" »" + emptySrcDir + "«");
          srcDirectories.remove(emptySrcDir);
        });
      }
    }
    catch (IOException ex)
    {
      throw new ScriptException(29, ex);
    }
  }

  private class PathAction implements Comparable<PathAction>
  {
    private final Path path;
    private final Op operation;

    private PathAction(Path path, Op operation)
    {
      this.path = path;
      this.operation = operation;
    }

    @Override
    public int compareTo(PathAction other)
    {
      final boolean isThisRmdir = Op.RMDIR.equals(this.operation);
      final boolean isOtherRmdir = Op.RMDIR.equals(other.operation);
      if (isThisRmdir)
      {
        if (isOtherRmdir)
        {
          return -this.path.compareTo(other.path);
        }
        else
        {
          return 1;
        }
      }
      else
      {
        if (isOtherRmdir)
        {
          return -1;
        }
        else
        {
          return this.path.compareTo(other.path);
        }
      }
    }

    @Override
    public boolean equals(Object object)
    {
      return object instanceof PathAction other ? this.compareTo(other) == 0 : false;
    }

    @Override
    public int hashCode()
    {
      return Objects.hash(path, operation);
    }

    private void run()
    {
      systemCommands.forEach(systemCommand ->
      {
        switch (operation)
        {
          case MKDIR ->
            systemCommand.addDir(path);
          case ADD ->
            systemCommand.addFile(path);
          case MOD ->
            systemCommand.changeFile(path);
          case DEL ->
            systemCommand.removeFile(path);
          case RMDIR ->
            systemCommand.removeDir(path);
          default ->
            throw new ScriptException(98, "Invalid Op: " + operation);
        }
      });
    }
  }

  /**
   * Applies the changes to the destination dir.
   *
   * @throws ScriptException if an {@link IOException} occurs or running a
   *                         system command fails
   */
  private void applyChanges()
  {
    Stream.of(
      srcDirectories.stream().filter(not(dstDirectories::contains)).map(dir -> new PathAction(dir, Op.MKDIR)),
      srcFiles.stream().filter(not(dstFiles::contains)).map(file -> new PathAction(file, Op.ADD)),
      srcFiles.stream().filter(dstFiles::contains).map(file -> new PathAction(file, Op.MOD)),
      dstFiles.stream().filter(not(srcFiles::contains)).map(file -> new PathAction(file, Op.DEL)),
      dstDirectories.stream().filter(not(srcDirectories::contains)).map(dir -> new PathAction(dir, Op.RMDIR)))
      .flatMap(s -> s)
      .sorted()
      .forEachOrdered(PathAction::run);
  }
}
