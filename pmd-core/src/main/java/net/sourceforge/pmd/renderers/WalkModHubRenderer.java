/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.renderers;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import net.sourceforge.pmd.RuleViolation;
import net.sourceforge.pmd.lang.rule.properties.StringProperty;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Incremental Renderer for WalkModHub in text format.
 *
 */
public class WalkModHubRenderer extends AbstractIncrementingRenderer {

    public static final String NAME = "WalkModHub";

    private String workingDir = "";

    private RevCommit fetchHead;

    private RevCommit lastAnalysis;

    private HttpClient httpclient;

    private Gson gson;

    private String host;

    private Git git;

    private String repository;

    //file variables

    private JsonArray previousIssues;
    private String location;
    private String remoteBranchToCompare;

    private BlameResult blameResult;

    private TextRenderer renderer;

    private List<DiffEntry> diffs;

    private static final String URL_FIELD = "url";

    private static final String HUB_PROTOCOL = "http://";

    public static final StringProperty HOST_PROPERTY = new StringProperty("host", "The host name (e.g localhost:4567)",
            "localhost:4567", 0);

    public static final StringProperty WORKING_DIR_PROPERTY = new StringProperty("workingDir",
            "The working directory (.git parent directory) ", ".", 1);

    /**
     * Default constructor of WalkModHubRenderer.
     * 
     * @throws IOException
     *             if the current execution directory cannot be resolved
     */
    public WalkModHubRenderer() {
        super("WalkModHub", "WalkModHub Integration");
        renderer = new TextRenderer();
        definePropertyDescriptor(HOST_PROPERTY);
        definePropertyDescriptor(WORKING_DIR_PROPERTY);
    }

    /**
     * Sets the host where WalkModHub is running.
     * 
     * @param host
     *            url of our host e.g locahost:4567
     */
    public void setHost(String url) {
        this.host = url;
    }

    @Override
    public void start() throws IOException {
        try {

            host = getProperty(HOST_PROPERTY);
            workingDir = new File(getProperty(WORKING_DIR_PROPERTY)).getCanonicalPath();
            resolveLastAnalysis();
            renderer.start();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void setWriter(Writer writer) {
        super.setWriter(writer);
        renderer.setWriter(getWriter());
    }

    /**
     * Resolves the current branch of the project.
     * 
     * @return the current branch
     * @throws IOException
     *             if git data cannot be read
     */
    protected String getBranch() throws IOException {
        return new GitUtils().getClosestRemoteBranch(git).getName();
    }

    private void prepare() throws IOException {
        if (git == null) {
            setGit(Git.open(new File(getProperty(WORKING_DIR_PROPERTY))));

        }

        if (httpclient == null) {
            setHttpClient(HttpClientBuilder.create().build());
        }
        gson = new Gson();
    }

    /**
     * Injects the HttpClient.
     * 
     * @param client
     *            http client
     */
    protected void setHttpClient(HttpClient client) {
        this.httpclient = client;
    }

    /**
     * Injects the git object.
     * 
     * @param git
     *            object
     */
    protected void setGit(Git git) {
        this.git = git;
    }

    /**
     * Returns the corresponging git object for an specific commit hash code.
     * 
     * @param git
     *            object where to ask the data
     * @param name
     *            sha of the commit
     * @return commit object
     * @throws IOException
     */
    protected RevCommit getCommit(Git git, String name) throws IOException {
        ObjectId oid = git.getRepository().resolve(name);

        RevWalk revWalk = new RevWalk(git.getRepository());
        RevCommit fetchHead = null;
        try {
            fetchHead = revWalk.parseCommit(oid);
        } catch (MissingObjectException e) {
            //nothing to do
        } catch (IncorrectObjectTypeException e) {
            //nothing to do
        }
        revWalk.dispose();
        return fetchHead;
    }

    /**
     * Resolves the repository id of the project asking for it to WalkModHub.
     * 
     * @return the repository id
     * @throws IOException
     */
    protected String getRepository() throws IOException {
        if (repository == null) {
            String url = git.getRepository().getConfig().getString("remote", "origin", URL_FIELD);

            List<NameValuePair> params = new LinkedList<NameValuePair>();
            params.add(new BasicNameValuePair(URL_FIELD, url));
            String paramString = URLEncodedUtils.format(params, "utf-8");

            HttpGet httpget = new HttpGet(HUB_PROTOCOL + host + "/repo?" + paramString);

            HttpResponse response = httpclient.execute(httpget);
            HttpEntity entity = response.getEntity();

            JsonObject repo = null;

            if (entity != null) {
                String data = IOUtils.toString(entity.getContent());
                repo = gson.fromJson(data, JsonObject.class);
                JsonElement urlInfo = repo.get("id");
                if (urlInfo != null) {
                    repository = urlInfo.getAsString();
                } else {
                    throw new RuntimeException("Missing configured repository on WalkModHub " + url);
                }
            }
        }

        return repository;
    }

    private void resolveLastAnalysis() throws IOException {
        prepare();
        fetchHead = getCommit(git, "origin/" + getBranch());

        lastAnalysis = getLastAnalysisFromHub();

        if (isPrevious(lastAnalysis, fetchHead)) {
            fetchHead = lastAnalysis;
            //we are in a CI context and modifying the same branch
        }
    }

    /**
     * Returns if the first commit was created before than the second.
     * 
     * @param commit
     *            first commit to compare
     * @param commit2
     *            second commit to compare
     * @return false if one of the two commits are null. Otherwise, compare the associated dates
     */
    protected boolean isPrevious(RevCommit commit, RevCommit commit2) {
        if (commit == null || commit2 == null) {
            return false;
        }
        return getDateFromCommit(commit).before(getDateFromCommit(commit2));
    }

    /**
     * Returns the last reported analysis into the WalkModHub.
     * 
     * @return the commit object corresponding to the last analysis in Hub
     * @throws IOException
     */
    protected RevCommit getLastAnalysisFromHub() throws IOException {
        URL url = new URL(HUB_PROTOCOL + host + "/analysis/" + getRepository() + "/" + getBranch());
        URLConnection con = url.openConnection();

        InputStreamReader in = new InputStreamReader(con.getInputStream());
        JsonObject result = gson.fromJson(in, JsonObject.class);
        remoteBranchToCompare = result.get("analyzedBranch").getAsString();
        return getCommit(git, result.get("commit").getAsString());

    }

    @Override
    public String defaultFileExtension() {
        return "txt";
    }

    private boolean isTouched(String fullPath) throws IOException {

        boolean result = false;
        location = getLocalLocation(fullPath);
        File file = new File(workingDir, location);
        if (file.exists()) {
            RevCommit lastFileCommit = getLastModification(location);
            result = isPrevious(fetchHead, lastFileCommit) || isEditedButStillNotCommitted(location);

            if (result) {
                previousIssues = getPreviousIssues(location);
                blameResult = getBlame(location);
                diffs = new GitUtils().getLastDiffsFromLocation(git, location, lastAnalysis, fetchHead);
            }
        }
        return result;
    }

    /**
     * Returns if there are still pending changes to commit
     * 
     * @param location
     *            file to analyze
     * @return if there are still pending changes to commit
     * @throws IOException
     *             when git problems appear
     */
    protected boolean isEditedButStillNotCommitted(String location) throws IOException {
        boolean result = false;
        try {
            Status status = git.status().addPath(location).call();
            result = !status.isClean();
        } catch (Exception e) {
            throw new IOException(e);
        }
        return result;
    }

    private RevCommit getLastModification(String location) throws IOException {
        RevCommit result = null;
        try {
            Iterable<RevCommit> logs = git.log().addPath(location).setMaxCount(1).call();
            Iterator<RevCommit> it = logs.iterator();
            result = it.next();
        } catch (NoHeadException e) {

        } catch (GitAPIException e) {

        }
        return result;
    }

    /**
     * Resolves the blame result for an specific file.
     * 
     * @param location
     *            relative file path
     * @return the blame result of the selected file
     * @throws IOException
     */
    protected BlameResult getBlame(String location) throws IOException {
        try {
            return git.blame().setFilePath(location).call();
        } catch (GitAPIException e) {
            throw new IOException("Error in git blame for " + location, e);
        }
    }

    /**
     * Resolves the relative location of the file.
     * 
     * @param file
     *            in an absolute path
     * @return relative path to the project directory
     * @throws IOException
     */
    protected String getLocalLocation(String file) throws IOException {
        if (file.startsWith(workingDir)) {
            return file.substring(new File(workingDir).getCanonicalPath().length() + 1);
        }
        return file;
    }

    /**
     * Resolves the previous issues for an specific location.
     * 
     * @param location
     *            relative path file to ask for
     * @return the array of issues previously analyzed
     * @throws IOException
     */
    protected JsonArray getPreviousIssues(String location) throws IOException {
        JsonArray previousIssues = null;
        String remoteBranchToCompare = getRemoteBranchToCompare();
        if (remoteBranchToCompare != null) {
            List<NameValuePair> params = new LinkedList<NameValuePair>();
            params.add(new BasicNameValuePair("location", location));
            String paramString = URLEncodedUtils.format(params, "utf-8");

            HttpGet httpget = new HttpGet(HUB_PROTOCOL + host + "/analysis/issues/" + repository + "/"
                    + getRemoteBranchToCompare() + "?" + paramString);

            HttpResponse response = httpclient.execute(httpget);
            HttpEntity entity = response.getEntity();

            if (entity != null) {
                String contents = IOUtils.toString(entity.getContent());

                previousIssues = gson.fromJson(contents, JsonArray.class);

            }
        }

        return previousIssues;

    }

    /**
     * Returns the remote branch to compare
     * 
     * @return remoteBranchToCompare
     */
    protected String getRemoteBranchToCompare() {
        return remoteBranchToCompare;
    }

    @Override
    public void renderFileViolations(Iterator<RuleViolation> violations) throws IOException {
        List<RuleViolation> newViolations = new LinkedList<RuleViolation>();
        if (violations.hasNext()) {
            RuleViolation rv = violations.next();
            if (isTouched(rv.getFilename())) {
                resolveViolation(rv.getFilename(), rv, newViolations);
                while (violations.hasNext()) {
                    rv = violations.next();
                    resolveViolation(rv.getFilename(), rv, newViolations);
                }
            }

        }
        if (!newViolations.isEmpty()) {
            renderer.renderFileViolations(newViolations.iterator());
        }

    }

    private Date getDateFromCommit(RevCommit lineCommit) {
        return lineCommit.getAuthorIdent().getWhen();
    }

    private void resolveViolation(String fullPath, RuleViolation violation, List<RuleViolation> newViolations)
            throws IOException {

        RevCommit lineCommit = blameResult.getSourceCommit(violation.getBeginLine() - 1);
        if (isPrevious(fetchHead, lineCommit)) {
            newViolations.add(violation);

        } else if (!isPreviouslyReported(violation)) {
            newViolations.add(violation);
        }

    }

    private boolean isPreviouslyReported(RuleViolation violation) throws IOException {
        boolean areEquivalent = false;
        if (previousIssues != null) {

            int index = 0;

            while (index < previousIssues.size() && !areEquivalent) {

                GitUtils.FileRegion from = toFileRegion((JsonObject) previousIssues.get(index));
                GitUtils.FileRegion to = toFileRegion(violation);
                areEquivalent = GitUtils.areEquivalentLines(git, diffs, from, to);

                index++;

            }
        }
        return areEquivalent;
    }

    private GitUtils.FileRegion toFileRegion(JsonObject next) {
        return new GitUtils.FileRegion(next.get("beginLine").getAsInt() - 1, next.get("beginColumn").getAsInt() - 1,
                next.get("endLine").getAsInt() - 1, next.get("endColumn").getAsInt() - 1);
    }

    private GitUtils.FileRegion toFileRegion(RuleViolation violation) {
        return new GitUtils.FileRegion(violation.getBeginLine() - 1, violation.getBeginColumn() - 1,
                violation.getEndLine() - 1, violation.getEndColumn() - 1);
    }

}
