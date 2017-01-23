/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.renderers;

import java.io.File;
import java.net.URI;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.junit.Assert;
import org.junit.Test;

import net.sourceforge.pmd.renderers.GitUtils.BranchResult;

import com.google.common.io.Files;

public class GitUtilsTest {

    @Test
    public void testClosestRemoteBranchWhenInit() throws Exception {
        File tmpDir = Files.createTempDir();
        Git git = Git.init().setDirectory(tmpDir).call();
        try {
            BranchResult result = new GitUtils().getClosestRemoteBranch(git);
            Assert.assertNotNull(result);

            Assert.assertEquals("master", result.getName());
            Assert.assertNull(result.getCommit());

        } finally {
            git.close();
            FileUtils.deleteDirectory(tmpDir);
        }
    }

    @Test
    public void testClosestRemoteBranchWhenInitAndClone() throws Exception {
        File tmpDir = Files.createTempDir();
        File tmpDirAux = Files.createTempDir();
        Git git = Git.init().setDirectory(tmpDir).call();
        git.close();

        git = Git.cloneRepository().setURI(new URI(tmpDir.getPath()).toString()).setDirectory(tmpDirAux).call();
        try {
            BranchResult result = new GitUtils().getClosestRemoteBranch(git);
            Assert.assertNotNull(result);

            Assert.assertEquals("master", result.getName());
            Assert.assertNull(result.getCommit());

        } finally {
            git.close();
            FileUtils.deleteDirectory(tmpDir);
            FileUtils.deleteDirectory(tmpDirAux);
        }
    }

    @Test
    public void testClosestRemoteBranchWhenCommitAndClone() throws Exception {
        File tmpDir = Files.createTempDir();
        File tmpDirAux = Files.createTempDir();
        Git git = Git.init().setDirectory(tmpDir).call();
        File aux = new File(tmpDir, "Foo.java");
        FileUtils.write(aux, "class Foo{}");

        git.add().addFilepattern("Foo.java").call();
        git.commit().setMessage("my commit").setAuthor("rpau", "rpau@company.com")
                .setCommitter("rpau", "rpau@company.com").call();

        git.close();

        git = Git.cloneRepository().setURI(new URI(tmpDir.getPath()).toString()).setDirectory(tmpDirAux).call();
        try {
            BranchResult result = new GitUtils().getClosestRemoteBranch(git);
            Assert.assertNotNull(result);

            Assert.assertEquals("master", result.getName());
            Assert.assertNotNull(result.getCommit());

        } finally {
            git.close();
            FileUtils.deleteDirectory(tmpDir);
            FileUtils.deleteDirectory(tmpDirAux);
        }
    }

    @Test
    public void testClosestRemoteBranchWhenCheckoutAndClone() throws Exception {
        File tmpDir = Files.createTempDir();
        File tmpDirAux = Files.createTempDir();
        Git git = Git.init().setDirectory(tmpDir).call();
        File aux = new File(tmpDir, "Foo.java");
        FileUtils.write(aux, "class Foo{}");

        git.add().addFilepattern("Foo.java").call();
        git.commit().setMessage("my commit").setAuthor("rpau", "rpau@company.com")
                .setCommitter("rpau", "rpau@company.com").call();

        git.branchCreate().setName("dev").call();

        git.close();

        git = Git.cloneRepository().setURI(new URI(tmpDir.getPath()).toString()).setCloneAllBranches(true)
                .setDirectory(tmpDirAux).call();
        try {

            git.getRepository().getAllRefs();

            git.checkout().setName("origin/dev").call();

            BranchResult result = new GitUtils().getClosestRemoteBranch(git);
            Assert.assertNotNull(result);

            Assert.assertEquals("dev", result.getName());
            Assert.assertNotNull(result.getCommit());

        } finally {
            git.close();
            FileUtils.deleteDirectory(tmpDir);
            FileUtils.deleteDirectory(tmpDirAux);
        }
    }

    @Test
    public void testClosestRemoteBranchWhenCloneAndCheckout() throws Exception {
        File tmpDir = Files.createTempDir();
        File tmpDirAux = Files.createTempDir();
        Git git = Git.init().setDirectory(tmpDir).call();
        File aux = new File(tmpDir, "Foo.java");
        FileUtils.write(aux, "class Foo{}");

        git.add().addFilepattern("Foo.java").call();
        git.commit().setMessage("my commit").setAuthor("rpau", "rpau@company.com")
                .setCommitter("rpau", "rpau@company.com").call();

        git.close();

        git = Git.cloneRepository().setURI(new URI(tmpDir.getPath()).toString()).setCloneAllBranches(true)
                .setDirectory(tmpDirAux).call();
        try {

            git.getRepository().getAllRefs();

            git.checkout().setName("origin/dev").setCreateBranch(true).call();

            BranchResult result = new GitUtils().getClosestRemoteBranch(git);
            Assert.assertNotNull(result);

            Assert.assertEquals("master", result.getName());
            Assert.assertNotNull(result.getCommit());

        } finally {
            git.close();
            FileUtils.deleteDirectory(tmpDir);
            FileUtils.deleteDirectory(tmpDirAux);
        }
    }
    
    @Test
    public void testClosestRemoteBranchWhenCloneAndMultipleCheckout() throws Exception {
        File tmpDir = Files.createTempDir();
        File tmpDirAux = Files.createTempDir();
        Git git = Git.init().setDirectory(tmpDir).call();
        File aux = new File(tmpDir, "Foo.java");
        FileUtils.write(aux, "class Foo{}");

        git.add().addFilepattern("Foo.java").call();
        git.commit().setMessage("my commit").setAuthor("rpau", "rpau@company.com")
                .setCommitter("rpau", "rpau@company.com").call();

        git.close();

        git = Git.cloneRepository().setURI(new URI(tmpDir.getPath()).toString()).setCloneAllBranches(true)
                .setDirectory(tmpDirAux).call();
        try {

            git.getRepository().getAllRefs();

            git.checkout().setName("origin/dev").setCreateBranch(true).call();
            
            
            aux = new File(tmpDirAux, "Foo.java");
            FileUtils.write(aux, "class Foo{ int x }");

            git.add().addFilepattern("Foo.java").call();
            git.commit().setMessage("my commit").setAuthor("rpau", "rpau@company.com")
                    .setCommitter("rpau", "rpau@company.com").call();
            
            git.checkout().setName("origin/feature").setCreateBranch(true).call();
           
            aux = new File(tmpDirAux, "Foo.java");
            FileUtils.write(aux, "class Foo{ int x; int y; }");

            git.add().addFilepattern("Foo.java").call();
            git.commit().setMessage("my commit").setAuthor("rpau", "rpau@company.com")
                    .setCommitter("rpau", "rpau@company.com").call();
          
            
            BranchResult result = new GitUtils().getClosestRemoteBranch(git);
            Assert.assertNotNull(result);

            Assert.assertEquals("master", result.getName());
            Assert.assertNotNull(result.getCommit());

        } finally {
            git.close();
            FileUtils.deleteDirectory(tmpDir);
            FileUtils.deleteDirectory(tmpDirAux);
        }
    }

}
