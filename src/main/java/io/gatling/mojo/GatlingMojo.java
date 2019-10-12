/**
 * Copyright 2011-2017 GatlingCorp (http://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file has been changed in the fork: gatling-maven-plugin-events
 */
package io.gatling.mojo;

import nl.stokpop.eventscheduler.EventScheduler;
import nl.stokpop.eventscheduler.EventSchedulerBuilder;
import nl.stokpop.eventscheduler.api.*;
import nl.stokpop.eventscheduler.exception.EventSchedulerException;
import org.apache.commons.exec.ExecuteException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.toolchain.Toolchain;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.SelectorUtils;
import org.codehaus.plexus.util.StringUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

import static io.gatling.mojo.MojoConstants.*;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Mojo to execute Gatling.
 */
@Mojo(name = "test",
  defaultPhase = LifecyclePhase.INTEGRATION_TEST,
  requiresDependencyResolution = ResolutionScope.TEST)
public class GatlingMojo extends AbstractGatlingExecutionMojo {

  /**
   * A name of a Simulation class to run.
   */
  @Parameter(property = "gatling.simulationClass")
  private String simulationClass;

  /**
   * Iterate over multiple simulations if more than one simulation file is found. By default false.
   * If multiple simulations are found but {@literal runMultipleSimulations} is false the execution will fail.
   */
  @Parameter(property = "gatling.runMultipleSimulations", defaultValue = "false")
  private boolean runMultipleSimulations;

  /**
   * List of include patterns to use for scanning. Includes all simulations by default.
   */
  @Parameter(property = "gatling.includes")
  private String[] includes;

  /**
   * List of exclude patterns to use for scanning. Excludes none by default.
   */
  @Parameter(property = "gatling.excludes")
  private String[] excludes;

  /**
   * Run simulation but does not generate reports. By default false.
   */
  @Parameter(property = "gatling.noReports", defaultValue = "false")
  private boolean noReports;

  /**
   * Generate the reports for the simulation in this folder.
   */
  @Parameter(property = "gatling.reportsOnly")
  private String reportsOnly;

  /**
   * A short description of the run to include in the report.
   */
  @Parameter(property = "gatling.runDescription")
  private String runDescription;

  /**
   * Will cause the project build to look successful, rather than fail, even
   * if there are Gatling test failures. This can be useful on a continuous
   * integration server, if your only option to be able to collect output
   * files, is if the project builds successfully.
   */
  @Parameter(property = "gatling.failOnError", defaultValue = "true")
  private boolean failOnError;

  /**
   * Continue execution of simulations despite assertion failure. If you have
   * some stack of simulations and you want to get results from all simulations
   * despite some assertion failures in previous one.
   */
  @Parameter(property = "gatling.continueOnAssertionFailure", defaultValue = "false")
  private boolean continueOnAssertionFailure;

  @Parameter(property = "gatling.useOldJenkinsJUnitSupport", defaultValue = "false")
  private boolean useOldJenkinsJUnitSupport;

  /**
   * Extra JVM arguments to pass when running Gatling.
   */
  @Parameter(property = "gatling.jvmArgs")
  private List<String> jvmArgs;

  /**
   * Override Gatling's default JVM args, instead of replacing them.
   */
  @Parameter(property = "gatling.overrideJvmArgs", defaultValue = "false")
  private boolean overrideJvmArgs;

  /**
   * Propagate System properties to forked processes.
   */
  @Parameter(property = "gatling.propagateSystemProperties", defaultValue = "true")
  private boolean propagateSystemProperties;

  /**
   * Extra JVM arguments to pass when running Zinc.
   */
  @Parameter(property = "gatling.compilerJvmArgs")
  private List<String> compilerJvmArgs;

  /**
   * Override Zinc's default JVM args, instead of replacing them.
   */
  @Parameter(property = "gatling.overrideCompilerJvmArgs", defaultValue = "false")
  private boolean overrideCompilerJvmArgs;

  /**
   * Extra options to be passed to scalac when compiling the Scala code
   */
  @Parameter(property = "gatling.extraScalacOptions")
  private List<String> extraScalacOptions;

  /**
   * Disable the Scala compiler, if scala-maven-plugin is already in charge
   * of compiling the simulations.
   */
  @Parameter(property = "gatling.disableCompiler", defaultValue = "false")
  private boolean disableCompiler;

  /**
   * Use this folder to discover simulations that could be run.
   */
  @Parameter(property = "gatling.simulationsFolder", defaultValue = "${project.basedir}/src/test/scala")
  private File simulationsFolder;

  /**
   * Use this folder as the folder where feeders are stored.
   */
  @Parameter(property = "gatling.resourcesFolder", defaultValue = "${project.basedir}/src/test/resources")
  private File resourcesFolder;

  @Parameter(defaultValue = "${plugin.artifacts}", readonly = true)
  private List<Artifact> artifacts;

  private Set<File> existingDirectories;

  /**
   * EventScheduler: Enable calls to EventScheduler.
   */
  @Parameter(property = "gatling.eventSchedulerEnabled", defaultValue = "false")
  private boolean eventSchedulerEnabled;

  /**
   * EventScheduler: properties for custom event implementations
   */
  @Parameter(property = "gatling.eventProperties")
  private Map<String, Properties> eventProperties;

  /**
   * EventScheduler: schedule script with events, one event per line, such as: PT1M|scale-down|replicas=2
   */
  @Parameter(property = "gatling.eventScheduleScript")
  private String eventScheduleScript;

  /**
   * EventScheduler: Name of application that is being tested.
   */
  @Parameter(property = "gatling.application", defaultValue = "UNKNOWN_APPLICATION")
  private String eventApplication;

  /**
   * EventScheduler: Test type for this test.
   */
  @Parameter(property = "gatling.testType", defaultValue = "UNKNOWN_TEST_TYPE")
  private String eventTestType;

  /**
   * EventScheduler: Test environment for this test.
   */
  @Parameter(property = "gatling.testEnvironment", defaultValue = "UNKNOWN_TEST_ENVIRONMENT")
  private String eventTestEnvironment;

  /**
   * EventScheduler: Name of product that is being tested.
   */
  @Parameter(property = "gatling.productName",  defaultValue = "ANONYMOUS_PRODUCT")
  private String eventProductName;

  /**
   * EventScheduler: Name of performance dashboard for this test.
   */
  @Parameter(property = "gatling.dashboardName", defaultValue = "ANONYMOUS_DASHBOARD")
  private String eventDashboardName;

  /**
   * EventScheduler: Test run id.
   */
  @Parameter(property = "gatling.testRunId",  defaultValue = "ANONYMOUS_TEST_ID")
  private String eventTestRunId;

  /**
   * EventScheduler: Build results url to post the results of this load test.
   */
  @Parameter(property = "gatling.buildResultsUrl",  defaultValue = "http://localhost")
  private String eventBuildResultsUrl;

  /**
   * EventScheduler: Build results url to post the results of this load test.
   */
  @Parameter(property = "gatling.eventUrl",  defaultValue = "http://localhost:8123")
  private String eventUrl;

  /**
   * EventScheduler: the release number of the product.
   */
  @Parameter(property = "gatling.productRelease",  defaultValue = "1.0.0-SNAPSHOT")
  private String eventProductRelease;

  /**
   * EventScheduler: Rampup time in seconds.
   */
  @Parameter(property = "gatling.rampupTimeInSeconds",  defaultValue = "30")
  private String eventRampupTimeInSeconds;

  /**
   * EventScheduler: Constant load time in seconds.
   */
  @Parameter(property = "gatling.constantLoadTimeInSeconds", defaultValue = "570")
  private String eventConstantLoadTimeInSeconds;

  /**
   * Executes Gatling simulations.
   */
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("Skipping gatling-maven-plugin");
      return;
    }

    boolean abortEventScheduler = false;
    final EventScheduler eventScheduler = eventSchedulerEnabled
            ? createEventScheduler()
            : null;

    if (eventSchedulerEnabled && eventScheduler != null) {
      eventScheduler.startSession();

      Runnable shutdowner = () -> {
        if (!eventScheduler.isSessionStopped()) {
          getLog().info("Shutdown Hook: abort event scheduler session!");
          eventScheduler.abortSession();
        }
      };
      Thread eventSchedulerShutdownThread = new Thread(shutdowner, "eventSchedulerShutdownThread");
      Runtime.getRuntime().addShutdownHook(eventSchedulerShutdownThread);

    }

    // Create results directories
    resultsFolder.mkdirs();
    existingDirectories = directoriesInResultsFolder();

    try {
      List<String> testClasspath = buildTestClasspath();

      Toolchain toolchain = toolchainManager.getToolchainFromBuildContext("jdk", session);
      if (!disableCompiler) {
        executeCompiler(compilerJvmArgs(), testClasspath, toolchain);
      }

      List<String> jvmArgs = gatlingJvmArgs();

      if (reportsOnly != null) {
        executeGatling(jvmArgs, gatlingArgs(null), testClasspath, toolchain);

      } else {
        List<String> simulations = simulations();
        iterateBySimulations(toolchain, jvmArgs, testClasspath, simulations);
      }

    } catch (Exception e) {
      if (failOnError) {
        abortEventScheduler = true;
        if (e instanceof GatlingSimulationAssertionsFailedException) {
          throw new MojoFailureException(e.getMessage(), e);
        } else if (e instanceof MojoFailureException) {
          throw (MojoFailureException) e;
        } else if (e instanceof MojoExecutionException) {
          throw (MojoExecutionException) e;
        } else {
          throw new MojoExecutionException("Gatling failed.", e);
        }
      } else {
        getLog().warn("There were some errors while running your simulation, but failOnError was set to false won't fail your build.");
      }
    } finally {
      recordSimulationResults();
      if (eventScheduler != null && abortEventScheduler) {
        eventScheduler.abortSession();
      }
    }
    if (eventScheduler != null) {
        eventScheduler.stopSession();
    }
    // TODO: check if results of test run are ok and fail or succeed this maven run
  }

  private Set<File> directoriesInResultsFolder() {
    File[] directories = resultsFolder.listFiles(File::isDirectory);
    return (directories == null)
            ? Collections.emptySet()
            : new HashSet<>(Arrays.asList(directories));
  }

  private void iterateBySimulations(Toolchain toolchain, List<String> jvmArgs, List<String> testClasspath, List<String> simulations) throws Exception {
    Exception exc = null;
    int simulationsCount = simulations.size();
    for (int i = 0; i < simulationsCount; i++) {
      try {
        executeGatling(jvmArgs, gatlingArgs(simulations.get(i)), testClasspath, toolchain);
      } catch (GatlingSimulationAssertionsFailedException e) {
        if (exc == null && i == simulationsCount - 1) {
          throw e;
        }

        if (continueOnAssertionFailure) {
          if (exc != null) {
            continue;
          }
          exc = e;
          continue;
        }
        throw e;
      }
    }

    if (exc != null) {
      getLog().warn("There were some errors while running your simulation, but continueOnAssertionFailure was set to true, so your simulations continue to perform.");
      throw exc;
    }
  }

  private void executeCompiler(List<String> zincJvmArgs, List<String> testClasspath, Toolchain toolchain) throws Exception {
    List<String> compilerClasspath = buildCompilerClasspath();
    compilerClasspath.addAll(testClasspath);
    List<String> compilerArguments = compilerArgs();

    Fork forkedCompiler = new Fork(COMPILER_MAIN_CLASS, compilerClasspath, zincJvmArgs, compilerArguments, toolchain, false, getLog());
    try {
      forkedCompiler.run();
    } catch (ExecuteException e) {
      throw new CompilationException(e);
    }
  }

  private void executeGatling(List<String> gatlingJvmArgs, List<String> gatlingArgs, List<String> testClasspath, Toolchain toolchain) throws Exception {
    Fork forkedGatling = new Fork(GATLING_MAIN_CLASS, testClasspath, gatlingJvmArgs, gatlingArgs, toolchain, propagateSystemProperties, getLog());
    try {
      forkedGatling.run();
    } catch (ExecuteException e) {
      if (e.getExitValue() == 2)
        throw new GatlingSimulationAssertionsFailedException(e);
      else
        throw e; /* issue 1482*/
    }
  }

  private void recordSimulationResults() throws MojoExecutionException {
    try {
      saveListOfNewRunDirectories();
      copyJUnitReports();
    } catch (IOException e) {
      throw new MojoExecutionException("Could not record simulation results.", e);
    }
  }

  private void saveListOfNewRunDirectories() throws IOException {
    Path resultsFile = resultsFolder.toPath().resolve(LAST_RUN_FILE);

    try (BufferedWriter writer = Files.newBufferedWriter(resultsFile)) {
      for (File directory : directoriesInResultsFolder()) {
        if (isNewDirectory(directory)) {
          writer.write(directory.getName() + System.lineSeparator());
        }
      }
    }
  }

  private boolean isNewDirectory(File directory) {
    return !existingDirectories.contains(directory);
  }

  private void copyJUnitReports() throws MojoExecutionException {

    try {
      if (useOldJenkinsJUnitSupport) {
        for (File directory: directoriesInResultsFolder()) {
          File jsDir = new File(directory, "js");
          if (jsDir.exists() && jsDir.isDirectory()) {
            File assertionFile = new File(jsDir, "assertions.xml");
            if (assertionFile.exists()) {
              File newAssertionFile = new File(resultsFolder, "assertions-" + directory.getName() + ".xml");
              Files.copy(assertionFile.toPath(), newAssertionFile.toPath(), COPY_ATTRIBUTES, REPLACE_EXISTING);
              getLog().info("Copying assertion file " + assertionFile.getCanonicalPath() + " to " + newAssertionFile.getCanonicalPath());
            }
          }
        }
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to copy JUnit reports", e);
    }
  }

  private List<String> buildCompilerClasspath() throws Exception {

    List<String> compilerClasspathElements = new ArrayList<>();
    for (Artifact artifact: artifacts) {
      String groupId = artifact.getGroupId();
      if (!groupId.startsWith("org.codehaus.plexus")
        && !groupId.startsWith("org.apache.maven")
        && !groupId.startsWith("org.sonatype")) {
        compilerClasspathElements.add(artifact.getFile().getCanonicalPath());
      }
    }

    String gatlingVersion = getVersion("io.gatling", "gatling-core");
    Set<Artifact> gatlingCompilerAndDeps = resolve("io.gatling", "gatling-compiler", gatlingVersion, true).getArtifacts();
    for (Artifact artifact : gatlingCompilerAndDeps) {
      compilerClasspathElements.add(artifact.getFile().getCanonicalPath());
    }

    // Add plugin jar to classpath (used by MainWithArgsInFile)
    compilerClasspathElements.add(MojoUtils.locateJar(GatlingMojo.class));
    return compilerClasspathElements;
  }

  private List<String> gatlingJvmArgs() {
    return computeArgs(jvmArgs, GATLING_JVM_ARGS, overrideJvmArgs);
  }

  private List<String> compilerJvmArgs() {
    return computeArgs(compilerJvmArgs, COMPILER_JVM_ARGS, overrideCompilerJvmArgs);
  }

  private List<String> computeArgs0(List<String> custom, List<String> defaults, boolean override) {
    if (custom.isEmpty()) {
      return defaults;
    }
    if (override) {
      List<String> merged = new ArrayList<>(custom);
      merged.addAll(defaults);
      return merged;
    }
    return custom;
  }

  private List<String> computeArgs(List<String> custom, List<String> defaults, boolean override) {
    List<String> result = new ArrayList<>(computeArgs0(custom, defaults, override));
    // force disable disableClassPathURLCheck because Debian messed up and takes forever to fix, see https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=911925
    result.add("-Djdk.net.URLClassPath.disableClassPathURLCheck=true");
    return result;
  }

  private List<String> simulations() throws MojoFailureException {
    // Solves the simulations, if no simulation file is defined
    if (simulationClass != null) {
      return Collections.singletonList(simulationClass);

    } else {
      List<String> simulations = resolveSimulations();

      if (simulations.isEmpty()) {
        getLog().error("No simulations to run");
        throw new MojoFailureException("No simulations to run");
      }

      if (simulations.size() > 1 && !runMultipleSimulations) {
        String message = "More than 1 simulation to run, need to specify one, or enable runMultipleSimulations";
        getLog().error(message);
        throw new MojoFailureException(message);
      }

      return simulations;
    }
  }

  private List<String> gatlingArgs(String simulationClass) throws Exception {
    // Arguments
    List<String> args = new ArrayList<>();
    addArg(args, "rsf", resourcesFolder.getCanonicalPath());
    addArg(args, "rf", resultsFolder.getCanonicalPath());
    addArg(args, "sf", simulationsFolder.getCanonicalPath());

    addArg(args, "rd", runDescription);

    if (noReports) {
      args.add("-nr");
    }

    addArg(args, "s", simulationClass);
    addArg(args, "ro", reportsOnly);

    return args;
  }

  private List<String> compilerArgs() throws Exception {
    List<String> args = new ArrayList<>();
    addArg(args, "sf", simulationsFolder.getCanonicalPath());
    addArg(args, "bf", compiledClassesFolder.getCanonicalPath());

    if (!extraScalacOptions.isEmpty()) {
      addArg(args, "eso", StringUtils.join(extraScalacOptions.iterator(), ","));
    }

    return args;
  }

  /**
   * Resolve simulation files to execute from the simulation folder.
   *
   * @return a comma separated String of simulation class names.
   */
  private List<String> resolveSimulations() {

    try {
      ClassLoader testClassLoader = new URLClassLoader(testClassPathUrls());

      Class<?> simulationClass = testClassLoader.loadClass("io.gatling.core.scenario.Simulation");
      List<String> includes = MojoUtils.arrayAsListEmptyIfNull(this.includes);
      List<String> excludes = MojoUtils.arrayAsListEmptyIfNull(this.excludes);

      List<String> simulationsClasses = new ArrayList<>();

      for (String classFile: compiledClassFiles()) {
        String className = pathToClassName(classFile);

        boolean isIncluded = includes.isEmpty() || match(includes, className);
        boolean isExcluded =  match(excludes, className);

        if (isIncluded && !isExcluded) {
          // check if the class is a concrete Simulation
          Class<?> clazz = testClassLoader.loadClass(className);
          if (simulationClass.isAssignableFrom(clazz) && isConcreteClass(clazz)) {
            simulationsClasses.add(className);
          }
        }
      }

      return simulationsClasses;

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean match(List<String> patterns, String string) {
    for (String pattern : patterns) {
      if (SelectorUtils.match(pattern, string)) {
        return true;
      }
    }
    return false;
  }

  private URL[] testClassPathUrls() throws DependencyResolutionRequiredException, MalformedURLException {

    List<String> testClasspathElements = mavenProject.getTestClasspathElements();

    URL[] urls = new URL[testClasspathElements.size()];
    for (int i = 0; i < testClasspathElements.size(); i++) {
      String testClasspathElement = testClasspathElements.get(i);
      URL url = Paths.get(testClasspathElement).toUri().toURL();
      urls[i] = url;
    }

    return urls;
  }

  private String[] compiledClassFiles() throws IOException {
    DirectoryScanner scanner = new DirectoryScanner();
    scanner.setBasedir(compiledClassesFolder.getCanonicalPath());
    scanner.setIncludes(new String[]{"**/*.class"});
    scanner.scan();
    String[] files = scanner.getIncludedFiles();
    Arrays.sort(files);
    return files;
  }

  private String pathToClassName(String path) {
    return path.substring(0, path.length() - ".class".length()).replace(File.separatorChar, '.');
  }

  private boolean isConcreteClass(Class<?> clazz) {
    return !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers());
  }

  private EventScheduler createEventScheduler() {

    EventSchedulerLogger logger = new EventSchedulerLogger() {
      @Override
      public void info(String message) {
        getLog().info(message);
      }

      @Override
      public void warn(String message) {
        getLog().warn(message);
      }

      @Override
      public void error(String message) {
        getLog().error(message);
      }

      @Override
      public void error(String message, Throwable throwable) {
        getLog().error(message, throwable);
      }

      @Override
      public void debug(final String message) {
        getLog().debug(message);
      }
    };

    TestContext context = new TestContextBuilder()
            .setTestType(eventTestType)
            .setTestEnvironment(eventTestEnvironment)
            .setTestRunId(eventTestRunId)
            .setCIBuildResultsUrl(eventBuildResultsUrl)
            .setApplicationRelease(eventProductRelease)
            .setRampupTimeInSeconds(eventRampupTimeInSeconds)
            .setConstantLoadTimeInSeconds(eventConstantLoadTimeInSeconds)
            .build();

    EventSchedulerSettings settings = new EventSchedulerSettingsBuilder()
            .setKeepAliveInterval(Duration.ofMinutes(2))
            .build();

    EventSchedulerBuilder builder = new EventSchedulerBuilder()
            .setEventSchedulerSettings(settings)
            .setTestContext(context)
            .setAssertResultsEnabled(true)
            .setCustomEvents(eventScheduleScript)
            .setLogger(logger);

    if (eventProperties != null) {
      eventProperties.forEach(
              (className, props) -> props.forEach(
                      (name, value) -> builder.addEventProperty(className, (String) name, (String) value)));
    }

    return builder.build();
  }

}
