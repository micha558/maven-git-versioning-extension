package me.qoomon.maven.gitversioning;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Math.ceil;
import static java.lang.Math.floor;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static me.qoomon.gitversioning.commons.GitRefType.BRANCH;
import static me.qoomon.gitversioning.commons.GitRefType.COMMIT;
import static me.qoomon.gitversioning.commons.GitRefType.TAG;
import static me.qoomon.gitversioning.commons.StringUtil.substituteText;
import static me.qoomon.gitversioning.commons.StringUtil.valueGroupMap;
import static me.qoomon.maven.gitversioning.BuildProperties.projectArtifactId;
import static me.qoomon.maven.gitversioning.GitVersioningMojo.GOAL;
import static me.qoomon.maven.gitversioning.GitVersioningMojo.asPlugin;
import static me.qoomon.maven.gitversioning.MavenUtil.pomFile;
import static me.qoomon.maven.gitversioning.MavenUtil.readModel;
import static me.qoomon.maven.gitversioning.MavenUtil.readXml;
import static me.qoomon.maven.gitversioning.MavenUtil.writeXml;
import static org.apache.maven.shared.utils.StringUtils.leftPad;
import static org.apache.maven.shared.utils.StringUtils.repeat;
import static org.apache.maven.shared.utils.logging.MessageUtils.buffer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import javax.enterprise.inject.Typed;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.building.Source;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.DefaultModelProcessor;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.session.scope.internal.SessionScope;
import org.codehaus.plexus.logging.Logger;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.inject.Key;
import com.google.inject.OutOfScopeException;

import de.pdark.decentxml.Document;
import de.pdark.decentxml.Element;
import me.qoomon.gitversioning.commons.GitSituation;
import me.qoomon.gitversioning.commons.GitUtil;
import me.qoomon.maven.gitversioning.Configuration.PropertyDescription;
import me.qoomon.maven.gitversioning.Configuration.VersionDescription;

// TODO add option to throw an error if git has non clean state

/**
 * Replacement for {@link ModelProcessor} to adapt versions.
 */
@Named("core-default")
@Singleton
@Typed(ModelProcessor.class)
@SuppressWarnings("CdiInjectionPointsInspection")
public class GitVersioningModelProcessor extends DefaultModelProcessor {

    private static final String OPTION_NAME_GIT_TAG = "git.tag";
    private static final String OPTION_NAME_GIT_BRANCH = "git.branch";
    private static final String OPTION_NAME_DISABLE = "versioning.disable";
    private static final String OPTION_UPDATE_POM = "versioning.updatePom";
    private static final String OPTION_PREFER_TAGS = "versioning.preferTags";

    private static final String DEFAULT_BRANCH_VERSION_FORMAT = "${branch}-SNAPSHOT";
    private static final String DEFAULT_TAG_VERSION_FORMAT = "${tag}";
    private static final String DEFAULT_COMMIT_VERSION_FORMAT = "${commit}";

    static final String GIT_VERSIONING_POM_NAME = ".git-versioned-pom.xml";

    @Inject
    private Logger logger;

    @Inject
    private SessionScope sessionScope;

    private static final Key<GitVersioningContext> SESSION_KEY = Key.get(GitVersioningContext.class); 
    //private boolean initialized = false;

    // --- following fields will be initialized by init() method -------------------------------------------------------

//    private MavenSession mavenSession; // can't be injected, cause it's not available before model read
//    private File mvnDirectory;
//    private GitSituation gitSituation;
    private Map<File, GitVersioningContext> contextMap = new HashMap<>();

    private InheritableThreadLocal<GitVersioningContext> threadContext = new InheritableThreadLocal<>();
    //private boolean disabled = false;
//    private boolean updatePomOption = false;
//    private GitVersionDetails gitVersionDetails;
//    private Map<String, PropertyDescription> gitVersioningPropertyDescriptionMap;
//    private Map<String, String> formatPlaceholderMap;
//    private Map<String, String> gitProjectProperties;
    //private Set<GAV> relatedProjects;


    // ---- other fields -----------------------------------------------------------------------------------------------

    //private final Set<File> projectModules = new HashSet<>();


    @Override
    public Model read(File input, Map<String, ?> options) throws IOException {
        final Model projectModel = super.read(input, options);
        return processModel(projectModel, options);
    }

    @Override
    public Model read(Reader input, Map<String, ?> options) throws IOException {
        final Model projectModel = super.read(input, options);
        return processModel(projectModel, options);
    }

    @Override
    public Model read(InputStream input, Map<String, ?> options) throws IOException {
        final Model projectModel = super.read(input, options);
        return processModel(projectModel, options);
    }

    private GitVersioningContext getContext(Model projectModel) {
    	File pomFile = projectModel.getPomFile();
		if (pomFile.getName().endsWith(".pom")) {
    		//repo-project - use last context in order to get all dependencies right
    		GitVersioningContext context = threadContext.get();//sessionScope.scope(SESSION_KEY, () -> null).get();
    		if (context == null) {
    			logger.error("context should not be null! " + projectModel);
    			context = new GitVersioningContext();
    			context.setDisabled(true);
    		}
        	threadContext.set(context);
			return context;
    	} else {
    		//local project
//    		projectModel.getPomFile();
        	GitVersioningContext context = contextMap.get(pomFile);
        	GitVersioningContext oldContext = context;
//        	if (context != null) {
        		//FIXME: reinit if git branch changed
        		if (oldContext != null && oldContext.getGitVersionDetails() != null && context.getGitVersionDetails() != null && oldContext.getGitVersionDetails().getRefName() != null && oldContext.getGitVersionDetails().getRefName().equals(context.getGitVersionDetails().getRefName())) {
        			//use old
        			context = oldContext;
        		}
        		if (context != null) {
        			contextMap.put(pomFile, context);
        		}
//        	}
        	threadContext.set(context);
    		//sessionScope.seed(GitVersioningContext.class, context);
    		return context;
    	}
/*    	File projectDirectory = projectModel.getProjectDirectory();
    	GitVersioningContext ctx = contextMap.get(projectDirectory);
    	if (ctx == null) {
    		ctx = init(projectModel);
    		if (ctx != null) {
    			contextMap.put(projectDirectory, ctx);
    		}
    	}
    	return ctx;
*/    	
    }

    private GitVersioningContext init(Model projectModel) {
    	try {
			GitVersioningContext ctx = new GitVersioningContext();
			logger.info("");
			String extensionId = BuildProperties.projectArtifactId() + ":" + BuildProperties.projectVersion();
			logger.info(extensionLogFormat(extensionId));

			// check if session is available
			try {
			    ctx.setMavenSession(sessionScope.scope(Key.get(MavenSession.class), null).get());
			} catch (OutOfScopeException ex) {
			    logger.warn("skip - no maven session present");
			    ctx.setDisabled(true);
			    return ctx;
			}

			MavenExecutionRequest request = ctx.getMavenSession().getRequest();
			if (request.getBaseDirectory() == null) {
				//File from local repo
			    ctx.setDisabled(true);
				return ctx;
			}
			File executionRootDirectory = new File(request.getBaseDirectory());
			logger.debug("execution root directory: " + executionRootDirectory);

			ctx.setMvnDirectory(executionRootDirectory);
			logger.debug(".mvn directory: " + ctx.getMvnDirectory());

			File configFile = new File(ctx.getMvnDirectory(), projectArtifactId() + ".xml");
			if (!configFile.exists()) {	//FIXME: alternate location
				ctx.setDisabled(true);
				return ctx;
			} 
			logger.debug("read config from " + configFile);
			Configuration config = readConfig(configFile);

			// check if extension is disabled by command option
			String commandOptionDisable = getCommandOption(ctx.getMavenSession(), OPTION_NAME_DISABLE);
			if (commandOptionDisable != null) {
				ctx.setDisabled(parseBoolean(commandOptionDisable));
			    if (ctx.isDisabled()) {
			        logger.info("skip - versioning is disabled by command option");
			        return ctx;
			    }
			} else {
			    // check if extension is disabled by config option
				ctx.setDisabled(config.disable != null && config.disable);
			    if (ctx.isDisabled()) {
			        logger.info("skip - versioning is disabled by config option");
			        return ctx;
			    }
			}
			ctx.setGitSituation(getGitSituation(ctx.getMavenSession(), executionRootDirectory));
			if (ctx.getGitSituation() == null) {
			    logger.warn("skip - project is not part of a git repository");
			    ctx.setDisabled(true);
			    return ctx;
			}
			logger.debug("git situation: " + ctx.getGitSituation().getRootDirectory());
			logger.debug("  root directory: " + ctx.getGitSituation().getRootDirectory());
			logger.debug("  head commit: " + ctx.getGitSituation().getHeadCommit());
			logger.debug("  head commit timestamp: " + ctx.getGitSituation().getHeadCommitTimestamp());
			logger.debug("  head branch: " + ctx.getGitSituation().getHeadBranch());
			logger.debug("  head tags: " + ctx.getGitSituation().getHeadTags());

			boolean preferTagsOption = getPreferTagsOption(ctx.getMavenSession(),config);
			logger.debug("option -  prefer tags: " + preferTagsOption);

			// determine git version details
			ctx.setGitVersionDetails(getGitVersionDetails(ctx.getGitSituation(), config, preferTagsOption));
			logger.info("git " + ctx.getGitVersionDetails().getRefType().name().toLowerCase() + ": " + buffer().strong(ctx.getGitVersionDetails().getRefName()));
			ctx.setGitVersioningPropertyDescriptionMap(ctx.getGitVersionDetails().getConfig().property.stream()
			        .collect(toMap(property -> property.name, property -> property)));

			ctx.setUpdatePomOption(getUpdatePomOption(ctx.getMavenSession(), config, ctx.getGitVersionDetails().getConfig()));
			logger.debug("option - update pom: " + ctx.isUpdatePomOption());

			// determine related projects
			ctx.setRelatedProjects(determineRelatedProjects(ctx, projectModel));
			logger.debug("related projects:");
			ctx.getRelatedProjects().forEach(gav -> logger.debug("  " + gav));

			// add session root project as initial module
			ctx.getProjectModules().add(projectModel.getPomFile());

			ctx.setFormatPlaceholderMap(generateFormatPlaceholderMapFromGit(ctx.getGitSituation(), ctx.getGitVersionDetails()));
			ctx.setGitProjectProperties(generateGitProjectProperties(ctx.getGitSituation(), ctx.getGitVersionDetails()));

			logger.info("");
			return ctx;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
    }

    // ---- model processing -------------------------------------------------------------------------------------------

    public Model processModel(Model projectModel, Map<String, ?> options) throws IOException {
        // set model pom file
        final Source pomSource = (Source) options.get(ModelProcessor.SOURCE);
        if (pomSource != null) {
            projectModel.setPomFile(new File(pomSource.getLocation()));
        } else {
            logger.debug("skip model - no project model pom file");
            return projectModel;
        }
        GitVersioningContext context = getContext(projectModel);
//        if (!initialized) {
//            init(projectModel);
//            initialized = true;
//        }
        

        if (context.isDisabled()) {
            return projectModel;
        }

        if (projectModel.getPomFile().getName().equals(GIT_VERSIONING_POM_NAME)) {
            logger.debug("skip model - git-versioned pom - " + projectModel.getPomFile());
            return projectModel;
        }

        if (GAV.of(projectModel).getVersion() == null) {
            logger.debug("skip model - can not determine project version - " + projectModel.getPomFile());
            return projectModel;
        }

        File canonicalProjectPomFile = projectModel.getPomFile().getCanonicalFile();

        if (!context.getProjectModules().contains(canonicalProjectPomFile)) {
            logger.debug("skip model - non project module - " + projectModel.getPomFile());
            return projectModel;
        }

        // return cached calculated project model if present
        Model cachedProjectModel = context.getSessionModelCache().get(canonicalProjectPomFile);
        if (cachedProjectModel != null) {
            return cachedProjectModel;
        }

        // add current project model to session project models
        context.getSessionModelCache().put(canonicalProjectPomFile, projectModel);

        // log project header
        logger.info(buffer().strong("--- ") + buffer().project(GAV.of(projectModel)).toString() + buffer().strong(" ---"));

        updateModel(context, projectModel);

        addGitProperties(context, projectModel);

        File gitVersionedPomFile = writePomFile(context, projectModel);
        if (context.isUpdatePomOption()) {
            logger.debug("updating original POM file");
            Files.copy(
                    gitVersionedPomFile.toPath(),
                    projectModel.getPomFile().toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        // git versioned pom can't be set as model pom right away, file
        // cause it will break plugins, that trying to update original pom file
        //   e.g. mvn versions:set -DnewVersion=1.0.0
        // That's why we need to add a build plugin that sets project pom file to git versioned pom file
        addBuildPlugin(projectModel);

        // add potential project modules
        for (File modulePomFile : getProjectModules(projectModel)) {
        	context.getProjectModules().add(modulePomFile.getCanonicalFile());
        }

        logger.info("");
        return projectModel;
    }

    private void updateModel(GitVersioningContext context, Model projectModel) {
        GAV originalProjectGAV = GAV.of(projectModel);
        
        updateVersion(context, projectModel.getParent());
        updateVersion(context, projectModel);
        updatePropertyValues(context, projectModel, originalProjectGAV);
        updateDependencyVersions(context, projectModel);
        updatePluginVersions(context, projectModel.getBuild());

        // profile section
        updateProfiles(context, projectModel.getProfiles(), originalProjectGAV);
    }

    private void updateProfiles(GitVersioningContext context, List<Profile> profiles, GAV originalProjectGAV) {
        // profile section
        if (!profiles.isEmpty()) {
            logger.info("profiles:");
            for (Profile profile : profiles) {
                logger.info("profile: " + profile.getId());
                updatePropertyValues(context, profile, originalProjectGAV);
                updateDependencyVersions(context, profile);
                updatePluginVersions(context, profile.getBuild());
            }
        }
    }

    private void updateVersion(GitVersioningContext context, Parent parent) {
        if (parent != null) {
            GAV parentGAV = GAV.of(parent);
            if (context.getRelatedProjects().contains(parentGAV)) {
                String gitVersion = getGitVersion(context, parentGAV);
                logger.debug("update parent version: " + parentGAV.getProjectId() + ":" + gitVersion);
                parent.setVersion(getGitVersion(context, parentGAV));
            }
        }
    }

    private void updateVersion(GitVersioningContext context, Model projectModel) {
        if (projectModel.getVersion() != null) {
            GAV projectGAV = GAV.of(projectModel);
            String gitVersion = getGitVersion(context, projectGAV);
            logger.info("update version: " + gitVersion);
            projectModel.setVersion(gitVersion);
        }
    }

    private void updatePropertyValues(GitVersioningContext context, ModelBase model, GAV originalProjectGAV) {
        // properties section
        model.getProperties().forEach((key, value) -> {
            String gitPropertyValue = getGitProjectPropertyValue(context, (String) key, (String) value, originalProjectGAV);
            if (!gitPropertyValue.equals(value)) {
                logger.info("update property " + key + ": " + gitPropertyValue);
                model.addProperty((String) key, gitPropertyValue);
            }
        });
    }

    private void updatePluginVersions(GitVersioningContext context, BuildBase build) {
        if (build == null) {
            return;
        }
        // plugins section
        {
            List<Plugin> relatedPlugins = filterRelatedPlugins(context, build.getPlugins());
            if (!relatedPlugins.isEmpty()) {
                logger.debug("plugins:");
                for (Plugin plugin : relatedPlugins) {
                    updateVersion(context, plugin);
                }
            }
        }

        // plugin management section
        PluginManagement pluginManagement = build.getPluginManagement();
        if (pluginManagement != null) {
            List<Plugin> relatedPlugins = filterRelatedPlugins(context, pluginManagement.getPlugins());
            if (!relatedPlugins.isEmpty()) {
                logger.debug("plugin management:");
                for (Plugin plugin : relatedPlugins) {
                    updateVersion(context, plugin);
                }
            }
        }
    }

    private void updateVersion(GitVersioningContext context, Plugin plugin) {
        if (plugin.getVersion() != null) {
            GAV pluginGAV = GAV.of(plugin);
            String gitVersion = getGitVersion(context, pluginGAV);
            logger.debug("update plugin version: " + pluginGAV.getProjectId() + ":" + gitVersion);
            plugin.setVersion(gitVersion);
        }
    }

    public List<Plugin> filterRelatedPlugins(GitVersioningContext context, List<Plugin> plugins) {
        return plugins.stream()
                .filter(it -> context.getRelatedProjects().contains(GAV.of(it)))
                .collect(toList());
    }

    private void updateDependencyVersions(GitVersioningContext context, ModelBase model) {
        // dependencies section
        {
            List<Dependency> relatedDependencies = filterRelatedDependencies(context, model.getDependencies());
            if (!relatedDependencies.isEmpty()) {
                logger.debug("dependencies:");
                for (Dependency dependency : relatedDependencies) {
                    updateVersion(context, dependency);
                }
            }
        }
        // dependency management section
        DependencyManagement dependencyManagement = model.getDependencyManagement();
        if (dependencyManagement != null) {
            List<Dependency> relatedDependencies = filterRelatedDependencies(context, dependencyManagement.getDependencies());
            if (!relatedDependencies.isEmpty()) {
                logger.debug("dependency management:");
                for (Dependency dependency : relatedDependencies) {
                    updateVersion(context, dependency);
                }
            }
        }
    }

    private void updateVersion(GitVersioningContext context, Dependency dependency) {
        if (dependency.getVersion() != null) {
            GAV dependencyGAV = GAV.of(dependency);
            String gitVersion = getGitVersion(context, dependencyGAV);
            logger.debug("update dependency version: " + dependencyGAV.getProjectId() + ":" + gitVersion);
            dependency.setVersion(gitVersion);
        }
    }

    public List<Dependency> filterRelatedDependencies(GitVersioningContext context, List<Dependency> dependencies) {
        return dependencies.stream()
                .filter(it -> context.getRelatedProjects().contains(GAV.of(it)))
                .collect(toList());
    }

    private void addGitProperties(GitVersioningContext context, Model projectModel) {
    	context.getGitProjectProperties().forEach(projectModel::addProperty);
    }

    private void addBuildPlugin(Model projectModel) {
        logger.debug("add version build plugin");

        Plugin plugin = asPlugin();

        PluginExecution execution = new PluginExecution();
        execution.setId(GOAL);
        execution.getGoals().add(GOAL);
        plugin.getExecutions().add(execution);

        if (projectModel.getBuild() == null) {
            projectModel.setBuild(new Build());
        }
        projectModel.getBuild().getPlugins().add(plugin);
    }


    // ---- versioning -------------------------------------------------------------------------------------------------

    private GitSituation getGitSituation(MavenSession mavenSession, File executionRootDirectory) throws IOException {
        GitSituation gitSituation = GitUtil.situation(executionRootDirectory);
        if (gitSituation == null) {
            return null;
        }
        String providedTag = getCommandOption(mavenSession, OPTION_NAME_GIT_TAG);
        if (providedTag != null) {
            logger.debug("set git head tag by command option: " + providedTag);
            gitSituation = GitSituation.Builder.of(gitSituation)
                    .setHeadBranch(null)
                    .setHeadTags(providedTag.isEmpty() ? emptyList() : singletonList(providedTag))
                    .build();
        }
        String providedBranch = getCommandOption(mavenSession, OPTION_NAME_GIT_BRANCH);
        if (providedBranch != null) {
            logger.debug("set git head branch by command option: " + providedBranch);
            gitSituation = GitSituation.Builder.of(gitSituation)
                    .setHeadBranch(providedBranch)
                    .build();
        }

        return gitSituation;
    }

    private static GitVersionDetails getGitVersionDetails(GitSituation gitSituation, Configuration config, boolean preferTags) {
        String headCommit = gitSituation.getHeadCommit();

        // detached tag
        if (!gitSituation.getHeadTags().isEmpty() && (gitSituation.isDetached() || preferTags)) {
            // sort tags by maven version logic
            List<String> sortedHeadTags = gitSituation.getHeadTags().stream()
                    .sorted(comparing(DefaultArtifactVersion::new)).collect(toList());
            for (VersionDescription tagConfig : config.tag) {
                for (String headTag : sortedHeadTags) {
                    if (tagConfig.pattern == null || headTag.matches(tagConfig.pattern)) {
                        return new GitVersionDetails(headCommit, TAG, headTag, tagConfig);
                    }
                }
            }
        }

        // detached commit
        if (gitSituation.isDetached()) {
            if (config.commit != null) {
                if (config.commit.pattern == null || headCommit.matches(config.commit.pattern)) {
                    return new GitVersionDetails(headCommit, COMMIT, headCommit, config.commit);
                }
            }

            // default config for detached head commit
            return new GitVersionDetails(headCommit, COMMIT, headCommit, new VersionDescription() {{
                versionFormat = DEFAULT_COMMIT_VERSION_FORMAT;
            }});
        }

        // branch
        {
            String headBranch = gitSituation.getHeadBranch();
            for (VersionDescription branchConfig : config.branch) {
                if (branchConfig.pattern == null || headBranch.matches(branchConfig.pattern)) {
                    return new GitVersionDetails(headCommit, BRANCH, headBranch, branchConfig);
                }
            }

            // default config for branch
            return new GitVersionDetails(headCommit, BRANCH, headBranch, new VersionDescription() {{
                versionFormat = DEFAULT_BRANCH_VERSION_FORMAT;
            }});
        }
    }

    private String getGitVersion(GitVersioningContext context, GAV originalProjectGAV) {
        final Map<String, String> placeholderMap = generateFormatPlaceholderMap(context, originalProjectGAV);
        return substituteText(context.getGitVersionDetails().getConfig().versionFormat, placeholderMap)
                // replace invalid version characters
                .replace("/", "-");
    }

    private String getGitProjectPropertyValue(GitVersioningContext context, String key, String originalValue, GAV originalProjectGAV) {
        PropertyDescription propertyConfig = context.getGitVersioningPropertyDescriptionMap().get(key);
        if (propertyConfig == null) {
            return originalValue;
        }
        final Map<String, String> placeholderMap = generateFormatPlaceholderMap(context, originalProjectGAV);
        placeholderMap.put("value", originalValue);
        return substituteText(propertyConfig.valueFormat, placeholderMap);
    }

    private Map<String, String> generateFormatPlaceholderMap(GitVersioningContext context, GAV originalProjectGAV) {
        final Map<String, String> placeholderMap = new HashMap<>();
        placeholderMap.putAll(context.getFormatPlaceholderMap());
        placeholderMap.putAll(generateFormatPlaceholderMapFromVersion(originalProjectGAV));
        context.getMavenSession().getUserProperties().forEach((key, value) -> placeholderMap.put((String) key, (String) value));
        return placeholderMap;
    }

    private static Map<String, String> generateFormatPlaceholderMapFromGit(GitSituation gitSituation, GitVersionDetails gitVersionDetails) {
        final Map<String, String> placeholderMap = new HashMap<>();

        String headCommit = gitSituation.getHeadCommit();
        placeholderMap.put("commit", headCommit);
        placeholderMap.put("commit.short", headCommit.substring(0, 7));

        ZonedDateTime headCommitDateTime = gitSituation.getHeadCommitDateTime();
        placeholderMap.put("commit.timestamp", String.valueOf(headCommitDateTime.toEpochSecond()));
        placeholderMap.put("commit.timestamp.year", String.valueOf(headCommitDateTime.getYear()));
        placeholderMap.put("commit.timestamp.month", leftPad(String.valueOf(headCommitDateTime.getMonthValue()), 2, "0"));
        placeholderMap.put("commit.timestamp.day", leftPad(String.valueOf(headCommitDateTime.getDayOfMonth()), 2, "0"));
        placeholderMap.put("commit.timestamp.hour", leftPad(String.valueOf(headCommitDateTime.getHour()), 2, "0"));
        placeholderMap.put("commit.timestamp.minute", leftPad(String.valueOf(headCommitDateTime.getMinute()), 2, "0"));
        placeholderMap.put("commit.timestamp.second", leftPad(String.valueOf(headCommitDateTime.getSecond()), 2, "0"));
        placeholderMap.put("commit.timestamp.datetime", headCommitDateTime.toEpochSecond() > 0
                ? headCommitDateTime.format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmmss")) : "00000000.000000");

        String refTypeName = gitVersionDetails.getRefType().name().toLowerCase();
        String refName = gitVersionDetails.getRefName();
        String refNameSlug = slugify(refName);
        placeholderMap.put("ref", refName);
        placeholderMap.put("ref.slug", refNameSlug);
        placeholderMap.put(refTypeName, refName);
        placeholderMap.put(refTypeName + ".slug", refNameSlug);
        String refPattern = gitVersionDetails.getConfig().pattern;
        if (refPattern != null) {
            Map<String, String> refNameValueGroupMap = valueGroupMap(refName, refPattern);
            placeholderMap.putAll(refNameValueGroupMap);
            placeholderMap.putAll(refNameValueGroupMap.entrySet().stream()
                    .collect(toMap(entry -> entry.getKey() + ".slug", entry -> slugify(entry.getValue()))));
        }

        placeholderMap.put("dirty", !gitSituation.isClean() ? "-DIRTY" : "");
        placeholderMap.put("dirty.snapshot", !gitSituation.isClean() ? "-SNAPSHOT" : "");

        return placeholderMap;
    }

    private static Map<String, String> generateFormatPlaceholderMapFromVersion(GAV originalProjectGAV) {
        Map<String, String> placeholderMap = new HashMap<>();
        String originalProjectVersion = originalProjectGAV.getVersion();
        placeholderMap.put("version", originalProjectVersion);
        placeholderMap.put("version.release", originalProjectVersion.replaceFirst("-SNAPSHOT$", ""));
        return placeholderMap;
    }

    private static Map<String, String> generateGitProjectProperties(GitSituation gitSituation, GitVersionDetails gitVersionDetails) {
        Map<String, String> properties = new HashMap<>();

        properties.put("git.commit", gitVersionDetails.getCommit());

        ZonedDateTime headCommitDateTime = gitSituation.getHeadCommitDateTime();
        properties.put("git.commit.timestamp", String.valueOf(headCommitDateTime.toEpochSecond()));
        properties.put("git.commit.timestamp.datetime", headCommitDateTime.toEpochSecond() > 0
                ? headCommitDateTime.format(ISO_INSTANT) : "0000-00-00T00:00:00Z");

        String refTypeName = gitVersionDetails.getRefType().name().toLowerCase();
        String refName = gitVersionDetails.getRefName();
        String refNameSlug = slugify(refName);
        properties.put("git.ref", refName);
        properties.put("git.ref.slug", refNameSlug);
        properties.put("git." + refTypeName, refName);
        properties.put("git." + refTypeName + ".slug", refNameSlug);

        properties.put("git.dirty", Boolean.toString(!gitSituation.isClean()));

        return properties;
    }


    // ---- configuration -------------------------------------------------------------------------------------------------

    private static File findMvnDirectory(File baseDirectory) throws IOException {
        File searchDirectory = baseDirectory;
        while (searchDirectory != null) {
            File mvnDir = new File(searchDirectory, ".mvn");
            if (mvnDir.exists()) {
                return mvnDir;
            }
            searchDirectory = searchDirectory.getParentFile();
        }

        throw new FileNotFoundException("Can not find .mvn directory in hierarchy of " + baseDirectory);
    }

    private static Configuration readConfig(File configFile) throws IOException {
        Configuration config = new XmlMapper().readValue(configFile, Configuration.class);

        for (VersionDescription versionDescription : config.branch) {
            if (versionDescription.versionFormat == null) {
                versionDescription.versionFormat = DEFAULT_BRANCH_VERSION_FORMAT;
            }
        }
        for (VersionDescription versionDescription : config.tag) {
            if (versionDescription.versionFormat == null) {
                versionDescription.versionFormat = DEFAULT_TAG_VERSION_FORMAT;
            }
        }
        if (config.commit != null) {
            if (config.commit.versionFormat == null) {
                config.commit.versionFormat = DEFAULT_COMMIT_VERSION_FORMAT;
            }
        }

        return config;
    }

    private String getCommandOption(MavenSession mavenSession, final String name) {
        String value = mavenSession.getUserProperties().getProperty(name);
        if (value == null) {
            String plainName = name.replaceFirst("^versioning\\.", "");
            String environmentVariableName = "VERSIONING_"
                    + String.join("_", plainName.split("(?=\\p{Lu})"))
                    .replaceAll("\\.", "_")
                    .toUpperCase();
            value = System.getenv(environmentVariableName);
        }
        if (value == null) {
            value = System.getProperty(name);
        }
        return value;
    }

    private boolean getPreferTagsOption(MavenSession mavenSession, final Configuration config) {
        final String preferTagsCommandOption = getCommandOption(mavenSession, OPTION_PREFER_TAGS);
        if (preferTagsCommandOption != null) {
            return parseBoolean(preferTagsCommandOption);
        }

        if (config.preferTags != null) {
            return config.preferTags;
        }

        return false;
    }

    private boolean getUpdatePomOption(MavenSession mavenSession, final Configuration config, final VersionDescription gitRefConfig) {
        final String updatePomCommandOption = getCommandOption(mavenSession, OPTION_UPDATE_POM);
        if (updatePomCommandOption != null) {
            return parseBoolean(updatePomCommandOption);
        }

        if (gitRefConfig.updatePom != null) {
            return gitRefConfig.updatePom;
        }

        if (config.updatePom != null) {
            return config.updatePom;
        }

        return false;
    }


    // ---- determine related projects ---------------------------------------------------------------------------------

    private Set<GAV> determineRelatedProjects(GitVersioningContext context, Model projectModel) throws IOException {
        HashSet<GAV> relatedProjects = new HashSet<>();
        determineRelatedProjects(context, projectModel, relatedProjects);
        return relatedProjects;
    }

    private void determineRelatedProjects(GitVersioningContext context, Model projectModel, Set<GAV> relatedProjects) throws IOException {
        GAV projectGAV = GAV.of(projectModel);
        if (relatedProjects.contains(projectGAV)) {
            return;
        }

        // add self
        relatedProjects.add(projectGAV);

        // check for related parent project by parent tag
        if (projectModel.getParent() != null) {
            GAV parentGAV = GAV.of(projectModel.getParent());
            File parentProjectPomFile = getParentProjectPomFile(projectModel);
        	
        	if (isRelatedPom(context, parentProjectPomFile)) {
                Model parentProjectModel = readModel(parentProjectPomFile);
                GAV parentProjectGAV = GAV.of(parentProjectModel);
                if (parentProjectGAV.equals(parentGAV)) {
                    determineRelatedProjects(context, parentProjectModel, relatedProjects);
                }
            }
        }

        // check for related parent project within parent directory
        Model parentProjectModel = searchParentProjectInParentDirectory(context, projectModel);
        if (parentProjectModel != null) {
            determineRelatedProjects(context, parentProjectModel, relatedProjects);
        }

        //  process modules
        for (File modulePomFile : getProjectModules(projectModel)) {
            if (modulePomFile.exists()) {
	        	Model moduleProjectModel = readModel(modulePomFile);
	            determineRelatedProjects(context, moduleProjectModel, relatedProjects);
            }
        }
    }

    /**
     * checks if <code>pomFile</code> is part of current maven and git context
     *
     * @param pomFile the pom file
     * @return true if <code>pomFile</code> is part of current maven and git context
     */
    private boolean isRelatedPom(GitVersioningContext context, File pomFile) throws IOException {
        return pomFile != null
                && pomFile.exists()
                && pomFile.isFile()
                // only project pom files ends in .xml, pom files from dependencies from repositories ends in .pom
                && pomFile.getName().endsWith(".xml")
                && pomFile.getCanonicalPath().startsWith(context.getMvnDirectory().getParentFile().getCanonicalPath() + File.separator)
                // only pom files within git directory are treated as project pom files
                && pomFile.getCanonicalPath().startsWith(context.getGitSituation().getRootDirectory().getCanonicalPath() + File.separator);
    }

    private Model searchParentProjectInParentDirectory(GitVersioningContext context, Model projectModel) throws IOException {
        // search for parent project by directory hierarchy
        File parentDirectoryPomFile = pomFile(projectModel.getProjectDirectory().getParentFile(), "pom.xml");
        if (parentDirectoryPomFile.exists() && isRelatedPom(context, parentDirectoryPomFile)) {
            // check if parent has module that points to current project directory
            Model parentDirectoryProjectModel = readModel(parentDirectoryPomFile);
            for (File modulePomFile : getProjectModules(parentDirectoryProjectModel)) {
                if (modulePomFile.getCanonicalFile().equals(projectModel.getPomFile().getCanonicalFile())) {
                    return parentDirectoryProjectModel;
                }
            }
        }
        return null;
    }

    private static File getParentProjectPomFile(Model projectModel) {
        if (projectModel.getParent() == null) {
            return null;
        }

        File parentProjectPomFile = pomFile(projectModel.getProjectDirectory(), projectModel.getParent().getRelativePath());
        if (parentProjectPomFile.exists()) {
            return parentProjectPomFile;
        }

        return null;
    }

    private static Set<File> getProjectModules(Model projectModel) {
        final Set<File> modules = new HashSet<>();

        // modules section
        for (String module : projectModel.getModules()) {
            modules.add(pomFile(projectModel.getProjectDirectory(), module));
        }

        // profiles section
        for (Profile profile : projectModel.getProfiles()) {

            // modules section
            for (String module : profile.getModules()) {
                modules.add(pomFile(projectModel.getProjectDirectory(), module));
            }
        }

        return modules;
    }


    // ---- generate git versioned pom file ----------------------------------------------------------------------------

    private File writePomFile(GitVersioningContext context, Model projectModel) throws IOException {
        File gitVersionedPomFile = new File(projectModel.getProjectDirectory(), GIT_VERSIONING_POM_NAME);
        logger.debug("generate " + gitVersionedPomFile);

        // read original pom file
        Document gitVersionedPomDocument = readXml(projectModel.getPomFile());
        Element projectElement = gitVersionedPomDocument.getChild("project");

        // update project
        updateParentVersion(projectElement, projectModel.getParent());
        updateVersion(projectElement, projectModel);
        updatePropertyValues(context, projectElement, projectModel);
        updateDependencyVersions(projectElement, projectModel);
        updatePluginVersions(projectElement, projectModel.getBuild());

        updateProfiles(context, projectElement, projectModel.getProfiles());

        writeXml(gitVersionedPomFile, gitVersionedPomDocument);

        return gitVersionedPomFile;
    }

    private static void updateParentVersion(Element projectElement, Parent parent) {
        Element parentElement = projectElement.getChild("parent");
        if (parentElement != null) {
            Element parentVersionElement = parentElement.getChild("version");
            parentVersionElement.setText(parent.getVersion());
        }
    }

    private static void updateVersion(Element projectElement, Model projectModel) {
        Element versionElement = projectElement.getChild("version");
        if (versionElement != null) {
            versionElement.setText(projectModel.getVersion());
        }
    }

    private void updatePropertyValues(GitVersioningContext context, Element element, ModelBase model) {
        // properties section
        Element propertiesElement = element.getChild("properties");
        if (propertiesElement != null) {
            Properties modelProperties = model.getProperties();
            context.getGitVersionDetails().getConfig().property.forEach(property -> {
                String propertyName = property.name;
                Element propertyElement = propertiesElement.getChild(propertyName);
                if (propertyElement != null) {
                    String pomPropertyValue = propertyElement.getText();
                    String modelPropertyValue = (String) modelProperties.get(propertyName);
                    if (!Objects.equals(modelPropertyValue, pomPropertyValue)) {
                        propertyElement.setText(modelPropertyValue);
                    }
                }
            });
        }
    }

    private static void updateDependencyVersions(Element element, ModelBase model) {
        // dependencies section
        {
            Element dependenciesElement = element.getChild("dependencies");
            if (dependenciesElement != null) {
                updateDependencyVersions(dependenciesElement, model.getDependencies());
            }
        }
        // dependencyManagement section
        Element dependencyManagementElement = element.getChild("dependencyManagement");
        if (dependencyManagementElement != null) {
            Element dependenciesElement = dependencyManagementElement.getChild("dependencies");
            if (dependenciesElement != null) {
                updateDependencyVersions(dependenciesElement, model.getDependencyManagement().getDependencies());
            }
        }
    }

    private static void updateDependencyVersions(Element dependenciesElement, List<Dependency> dependencies) {
        final Map<String, String> dependencyVersionMap = dependencies.stream()
                .filter(it -> it.getVersion() != null)
                .collect(toMap(it -> it.getGroupId() + ":" + it.getArtifactId(), Dependency::getVersion));

        for (Element dependencyElement : dependenciesElement.getChildren()) {
            String dependencyGroupId = dependencyElement.getChild("groupId").getText();
            String dependencyArtifactId = dependencyElement.getChild("artifactId").getText();
            Element dependencyVersionElement = dependencyElement.getChild("version");
            if (dependencyVersionElement != null) {
                dependencyVersionElement.setText(dependencyVersionMap.get(dependencyGroupId + ":" + dependencyArtifactId));
            }
        }
    }

    private static void updatePluginVersions(Element projectElement, BuildBase build) {
        // build section
        Element buildElement = projectElement.getChild("build");
        if (buildElement != null) {
            // plugins section
            {
                Element pluginsElement = buildElement.getChild("plugins");
                if (pluginsElement != null) {
                    updatePluginVersions(pluginsElement, build.getPlugins());
                }
            }
            // pluginManagement section
            Element pluginsManagementElement = buildElement.getChild("pluginsManagement");
            if (pluginsManagementElement != null) {
                Element pluginsElement = pluginsManagementElement.getChild("plugins");
                if (pluginsElement != null) {
                    updatePluginVersions(pluginsElement, build.getPluginManagement().getPlugins());
                }
            }
        }
    }

    private static void updatePluginVersions(Element pluginsElement, List<Plugin> plugins) {
        final Map<String, String> pluginVersionMap = plugins.stream()
                .filter(it -> it.getVersion() != null)
                .collect(toMap(it -> it.getGroupId() + ":" + it.getArtifactId(), Plugin::getVersion));

        for (Element pluginElement : pluginsElement.getChildren()) {
            Element pluginGroupIdElement = pluginElement.getChild("groupId");
            Element pluginArtifactIdElement = pluginElement.getChild("artifactId");
            Element pluginVersionElement = pluginElement.getChild("version");
            if (pluginVersionElement != null) {
                //  a plugin definition is valid even without groupId specified, therefore groupId element might not be present.
                String pluginGroupId = pluginGroupIdElement != null ? pluginGroupIdElement.getText() : null;
                String pluginArtifactId = pluginArtifactIdElement.getText();
                pluginVersionElement.setText(pluginVersionMap.get(pluginGroupId + ":" + pluginArtifactId));
            }
        }
    }

    private void updateProfiles(GitVersioningContext context, Element projectElement, List<Profile> profiles) {
        Element profilesElement = projectElement.getChild("profiles");
        if (profilesElement != null) {
            Map<String, Profile> profileMap = profiles.stream()
                    .collect(toMap(Profile::getId, it -> it));
            for (Element profileElement : profilesElement.getChildren("profile")) {
                Profile profile = profileMap.get(profileElement.getChild("id").getText());
                updatePropertyValues(context, profileElement, profile);
                updateDependencyVersions(profileElement, profile);
                updatePluginVersions(profileElement, profile.getBuild());
            }
        }
    }


    // ---- misc -------------------------------------------------------------------------------------------------------

    private static String extensionLogFormat(String extensionId) {
        int extensionIdPadding = 72 - 2 - extensionId.length();
        int extensionIdPaddingLeft = (int) ceil(extensionIdPadding / 2.0);
        int extensionIdPaddingRight = (int) floor(extensionIdPadding / 2.0);
        return buffer().strong(repeat("-", extensionIdPaddingLeft))
                + " " + buffer().mojo(extensionId) + " "
                + buffer().strong(repeat("-", extensionIdPaddingRight));
    }

    private static String slugify(String value) {
        return value
                .replace("/", "-")
                .toLowerCase();
    }
}
