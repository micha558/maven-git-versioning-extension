package me.qoomon.maven.gitversioning;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;

import me.qoomon.gitversioning.commons.GitSituation;
import me.qoomon.maven.gitversioning.Configuration.PropertyDescription;

public class GitVersioningContext {

    private MavenSession mavenSession; // can't be injected, cause it's not available before model read
    private File mvnDirectory;
    private GitSituation gitSituation;
    private Set<GAV> relatedProjects;
    private final Set<File> projectModules = new HashSet<>();
    private boolean disabled = false;
    private boolean updatePomOption = false;
    private GitVersionDetails gitVersionDetails;
    private Map<String, PropertyDescription> gitVersioningPropertyDescriptionMap;
    private Map<String, String> formatPlaceholderMap;
    private Map<String, String> gitProjectProperties;
    private final Map<File, Model> sessionModelCache = new HashMap<>();

	/**
	 * @return the mavenSession
	 */
	public MavenSession getMavenSession() {
		return mavenSession;
	}
	/**
	 * @param mavenSession the mavenSession to set
	 */
	public void setMavenSession(MavenSession mavenSession) {
		this.mavenSession = mavenSession;
	}
	/**
	 * @return the mvnDirectory
	 */
	public File getMvnDirectory() {
		return mvnDirectory;
	}
	/**
	 * @param mvnDirectory the mvnDirectory to set
	 */
	public void setMvnDirectory(File mvnDirectory) {
		this.mvnDirectory = mvnDirectory;
	}
	/**
	 * @return the gitSituation
	 */
	public GitSituation getGitSituation() {
		return gitSituation;
	}
	/**
	 * @param gitSituation the gitSituation to set
	 */
	public void setGitSituation(GitSituation gitSituation) {
		this.gitSituation = gitSituation;
	}
	/**
	 * @return the relatedProjects
	 */
	public Set<GAV> getRelatedProjects() {
		return relatedProjects;
	}
	/**
	 * @param relatedProjects the relatedProjects to set
	 */
	public void setRelatedProjects(Set<GAV> relatedProjects) {
		this.relatedProjects = relatedProjects;
	}
	/**
	 * @return the projectModules
	 */
	public Set<File> getProjectModules() {
		return projectModules;
	}
	/**
	 * @return the disabled
	 */
	public boolean isDisabled() {
		return disabled;
	}
	/**
	 * @param disabled the disabled to set
	 */
	public void setDisabled(boolean disabled) {
		this.disabled = disabled;
	}
	/**
	 * @return the updatePomOption
	 */
	public boolean isUpdatePomOption() {
		return updatePomOption;
	}
	/**
	 * @param updatePomOption the updatePomOption to set
	 */
	public void setUpdatePomOption(boolean updatePomOption) {
		this.updatePomOption = updatePomOption;
	}
	/**
	 * @return the gitVersionDetails
	 */
	public GitVersionDetails getGitVersionDetails() {
		return gitVersionDetails;
	}
	/**
	 * @param gitVersionDetails the gitVersionDetails to set
	 */
	public void setGitVersionDetails(GitVersionDetails gitVersionDetails) {
		this.gitVersionDetails = gitVersionDetails;
	}
	/**
	 * @return the gitVersioningPropertyDescriptionMap
	 */
	public Map<String, PropertyDescription> getGitVersioningPropertyDescriptionMap() {
		return gitVersioningPropertyDescriptionMap;
	}
	/**
	 * @param gitVersioningPropertyDescriptionMap the gitVersioningPropertyDescriptionMap to set
	 */
	public void setGitVersioningPropertyDescriptionMap(
			Map<String, PropertyDescription> gitVersioningPropertyDescriptionMap) {
		this.gitVersioningPropertyDescriptionMap = gitVersioningPropertyDescriptionMap;
	}
	/**
	 * @return the formatPlaceholderMap
	 */
	public Map<String, String> getFormatPlaceholderMap() {
		return formatPlaceholderMap;
	}
	/**
	 * @param formatPlaceholderMap the formatPlaceholderMap to set
	 */
	public void setFormatPlaceholderMap(Map<String, String> formatPlaceholderMap) {
		this.formatPlaceholderMap = formatPlaceholderMap;
	}
	/**
	 * @return the gitProjectProperties
	 */
	public Map<String, String> getGitProjectProperties() {
		return gitProjectProperties;
	}
	/**
	 * @param gitProjectProperties the gitProjectProperties to set
	 */
	public void setGitProjectProperties(Map<String, String> gitProjectProperties) {
		this.gitProjectProperties = gitProjectProperties;
	}
	/**
	 * @return the sessionModelCache
	 */
	public Map<File, Model> getSessionModelCache() {
		return sessionModelCache;
	}

    
    
}
