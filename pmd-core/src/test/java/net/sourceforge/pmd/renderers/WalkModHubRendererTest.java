/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.renderers;

import java.io.IOException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.revwalk.RevCommit;
import org.mockito.Mockito;

import com.google.gson.JsonArray;

public class WalkModHubRendererTest extends AbstractRendererTst {

    @Override
    public Renderer getRenderer() {
        final RevCommit commit = Mockito.mock(RevCommit.class);
        final JsonArray previousIssues = new JsonArray();
        final BlameResult blame = Mockito.mock(BlameResult.class);
        WalkModHubRenderer instance;

        instance = new WalkModHubRenderer() {
            protected String getBranch() {
                return "master";
            }

            protected RevCommit getCommit(Git git, String name) throws IOException {
                return commit;
            }

            protected String getRepository() throws IOException {
                return "123";
            }

            protected RevCommit getLastAnalysisFromHub() throws IOException {
                return commit;
            }

            protected boolean isPrevious(RevCommit commit, RevCommit commit2) {
                return false;
            }

            protected JsonArray getPreviousIssues(String location) throws IOException {
                return previousIssues;
            }

            protected BlameResult getBlame(String location) throws IOException {
                return blame;
            }

            protected boolean isEditedButStillNotCommitted(String location) throws IOException {
                return false;
            }
        };

        Git git = Mockito.mock(Git.class);
        instance.setGit(git);
        return instance;
    }

    @Override
    public String getExpected() {

        return "";
    }

    @Override
    public String getExpectedEmpty() {
        return "";
    }

    @Override
    public String getExpectedMultiple() {
        return "";
    }

}
