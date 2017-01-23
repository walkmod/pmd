/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.renderers;

import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * Utility class for Git repositories.
 *
 */
public class GitUtils {

    /**
     * Default constructor for git operations.
     */
    public GitUtils() {
    }

    private BranchResult getClosestBranch(Repository repo, Map<String, Ref> refList, RevCommit parent)
            throws IOException {
        RevWalk walk = new RevWalk(repo);
        Set<String> remoteBranches = refList.keySet();
        BranchResult result = null;
        Iterator<String> it = remoteBranches.iterator();
        while (it.hasNext()) {
            String aux = it.next();
            ObjectId branchId = repo.resolve(aux);
            RevCommit branchCommit = walk.parseCommit(branchId);
            if (walk.isMergedInto(parent, branchCommit)) {
                if (result == null || result.getWhen().before(branchCommit.getAuthorIdent().getWhen())) {
                    result = new BranchResult(aux, branchCommit);

                }
            }
        }
        return result;
    }

    private BranchResult getClosestBranch(Map<String, Ref> refList, Repository repo, String commitSha)
            throws IOException {
        BranchResult result = null;

        RevWalk walk = new RevWalk(repo);
        ObjectId oid = repo.resolve(commitSha);
        if (oid != null) {
            RevCommit commit = walk.parseCommit(oid);
            RevCommit[] parents = commit.getParents();

            for (int i = 0; i < parents.length; i++) {
                BranchResult parentBranch = getClosestBranch(repo, refList, parents[i]);
                if (result == null || result.getWhen().before(parentBranch.getWhen())) {
                    result = parentBranch;
                }
            }

        }
        return result;
    }

    /**
     * Returns the closest remote branch for an specific Git repository.
     * 
     * @param git
     *            repository to analyze
     * @return the closest remote branch
     * @throws IOException
     *             if git data cannot be read.
     */
    public BranchResult getClosestRemoteBranch(Git git) throws IOException {
        BranchResult result = null;
        Repository repo = git.getRepository();
        String currentBranch = repo.getBranch();
        Map<String, Ref> refList = repo.getRefDatabase().getRefs(Constants.R_REMOTES);
        Collection<Ref> references = refList.values();
        boolean found = false;

        Iterator<Ref> it = references.iterator();
        while (it.hasNext() && !found) {
            Ref ref = it.next();
            RevCommit commit = new RevWalk(repo).parseCommit(ref.getObjectId());
            found = commit.getName().equals(currentBranch);
            if (found) {
                result = new BranchResult(repo.shortenRemoteBranchName(ref.getName()), commit);

            }
        }

        if (!found) {

            result = getClosestBranch(refList, repo, currentBranch);
            if (result == null) {
                ObjectId masterCommit = repo.resolve("master");
                RevCommit commit = null;
                if (masterCommit != null) {
                    commit = new RevWalk(repo).parseCommit(masterCommit);
                }
                result = new BranchResult("master", commit);
            }

        }

        return result;
    }

    public static class BranchResult {

        private String name;

        private RevCommit commit;

        public BranchResult(String bname, RevCommit bcommit) {
            this.name = bname;
            this.commit = bcommit;
        }

        public String getName() {
            return name;
        }

        public RevCommit getCommit() {
            return commit;
        }

        public Date getWhen() {
            if (commit != null) {
                return commit.getAuthorIdent().getWhen();
            }
            return null;
        }

    }

    /**
     * Returns the list of differences between two commits.
     * 
     * @param git
     *            object to apply git queries
     * @param lastAnalysis
     *            last commit to compare
     * @param fecthHead
     *            current commit
     * @return the list of differences between two commits
     * @throws IOException
     *             if git data cannot be read.
     */
    public List<DiffEntry> getLastDiffs(Git git, RevCommit lastAnalysis, RevCommit fecthHead) throws IOException {
        ObjectReader reader = git.getRepository().newObjectReader();
        CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();

        CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
        ObjectId newTree = fecthHead.getTree().getId(); // equals oldCommit.getTree()
        newTreeIter.reset(reader, newTree);

        ObjectId oldTree = lastAnalysis.getTree().getId(); // equals newCommit.getTree()
        oldTreeIter.reset(reader, oldTree);

        DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
        df.setRepository(git.getRepository());

        List<DiffEntry> entries = df.scan(oldTreeIter, newTreeIter);
        return entries;
    }

    /**
     * Returns the differences between two different commits for an specific file.
     * 
     * @param git
     *            object to apply git queries
     * @param location
     *            file where to to filter the differences
     * @param lastAnalysis
     *            last commit to compare with
     * @param fecthHead
     *            current processing commit
     * @return list of differences
     * @throws IOException
     *             if git data cannot be read.
     */
    public List<DiffEntry> getLastDiffsFromLocation(Git git, String location, RevCommit lastAnalysis,
            RevCommit fecthHead) throws IOException {
        List<DiffEntry> diffs = getLastDiffs(git, lastAnalysis, fecthHead);
        List<DiffEntry> result = new LinkedList<DiffEntry>();
        for (DiffEntry entry : diffs) {
            if (entry.getNewPath().equals(location)) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Returns if two lines of code belonging to different commits corresponds are the same.
     * 
     * @param git
     *            object to apply git queries
     * @param diffs
     *            the list of differences for an specific file
     * @param from
     *            the original state of a code region
     * @param to
     *            the final state of a code region
     * @return of two lines of code are equivalent
     * @throws CorruptObjectException
     *             if git data cannot be read.
     * @throws MissingObjectException
     *             if git data cannot be read.
     * @throws IOException
     *             if git data cannot be read.
     */
    public static boolean areEquivalentLines(Git git, List<DiffEntry> diffs, FileRegion from, FileRegion to)
            throws CorruptObjectException, MissingObjectException, IOException {

        DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
        df.setRepository(git.getRepository());
        df.setDiffComparator(RawTextComparator.DEFAULT);
        df.setDetectRenames(false);

        for (DiffEntry entry : diffs) {
            if (entry.getChangeType().equals(ChangeType.MODIFY)) {
                FileHeader fh = df.toFileHeader(entry);
                EditList editList = fh.toEditList();
                Iterator<Edit> it = editList.iterator();

                while (it.hasNext()) {

                    Edit edit = it.next();

                    if (from.isAfter(edit.getBeginA())) {
                        int linesDeleted = edit.getEndA() - edit.getBeginA();
                        int linesAdded = edit.getEndB() - edit.getBeginB();

                        from.move(linesAdded - linesDeleted);

                    } else if (from.includesLine(edit.getBeginA()) && to.includesLine(edit.getBeginB())) {
                        // it is a different commit, so.. these are replaced

                        return true;
                    }

                }
            }
        }
        return from.startsAtSameLine(to);
    }

    /**
     * Data structure to represent file regions.
     *
     */
    public static class FileRegion {

        private int beginLine;

        private int beginColumn;

        private int endLine;

        private int endColumn;

        /**
         * File region constructor.
         * 
         * @param bLine
         *            begin line
         * @param bColumn
         *            begin column
         * @param eLine
         *            end line
         * @param eColumn
         *            end column
         */
        public FileRegion(final int bLine, final int bColumn, final int eLine, final int eColumn) {
            super();
            this.beginLine = bLine;
            this.beginColumn = bColumn;
            this.endLine = eLine;
            this.endColumn = eColumn;
        }

        /**
         * Returns of the specified line is in the region.
         * 
         * @param line
         *            to check
         * @return true if it is in the region
         */
        public boolean includesLine(int line) {
            return line >= beginLine && line <= endLine;

        }

        /**
         * Move the internal positions an specific number of lines.
         * 
         * @param lines
         *            to move
         */
        public void move(int lines) {
            beginLine += lines;
            endLine += lines;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof FileRegion) {
                FileRegion aux = (FileRegion) o;
                if (beginLine == aux.beginLine && beginColumn == aux.beginColumn && endLine == aux.endLine
                        && endColumn == aux.endColumn) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        /**
         * Returns if two file regions starts in the same line.
         * 
         * @param rf
         *            file region to compare
         * @return true if start in the same line
         */
        public boolean startsAtSameLine(FileRegion rf) {
            return this.beginLine == rf.beginLine;
        }

        /**
         * Returns true if the begin line is after the line.
         * 
         * @param line
         *            to compare
         * @return true if the begin line is after the line
         */
        public boolean isAfter(int line) {
            return line < beginLine;
        }

        /**
         * Returns true if the begin line is before the line.
         * 
         * @param line
         *            line to compare
         * @return the begin line is before the line
         */
        public boolean isBefore(int line) {
            return line > beginLine;
        }

        /**
         * Returns the begin line of the region.
         * 
         * @return the begin line
         */
        public int getBeginLine() {
            return beginLine;
        }

        /**
         * Sets the begin line of a region.
         * 
         * @param line
         *            begin line to set
         */
        public void setBeginLine(int line) {
            this.beginLine = line;
        }

        /**
         * Returns the begin column of the region.
         * 
         * @return the begin column of the region
         */
        public int getBeginColumn() {
            return beginColumn;
        }

        /**
         * Sets the begin column of the region.
         * 
         * @param column
         *            the begin column of the region
         */
        public void setBeginColumn(int column) {
            this.beginColumn = column;
        }

        /**
         * Returns the end line of the region.
         * 
         * @return the end line of the region
         */
        public int getEndLine() {
            return endLine;
        }

        /**
         * Sets the end line of the region.
         * 
         * @param line
         *            end line to set
         */
        public void setEndLine(int line) {
            this.endLine = line;
        }

        /**
         * Returns the end column of the region.
         * 
         * @return end column of the region
         */
        public int getEndColumn() {
            return endColumn;
        }

        /**
         * Sets the end column of the region.
         * 
         * @param column
         *            end column of the region
         */
        public void setEndColumn(int column) {
            this.endColumn = column;
        }

    }

}
