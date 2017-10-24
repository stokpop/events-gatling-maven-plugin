/*
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
 */
package io.gatling.mojo;

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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.gatling.mojo.MojoConstants.*;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Arrays.asList;

/**
 * Mojo to execute Gatling.
 */
@Mojo(name = "execute",
  defaultPhase = LifecyclePhase.INTEGRATION_TEST,
  requiresDependencyResolution = ResolutionScope.TEST)
public class GatlingMojo extends AbstractGatlingMojo {

  /**
   * Run simulation but does not generate reports. By default false.
   */
  @Parameter(property = "gatling.noReports", alias = "nr", defaultValue = "false")
  private boolean noReports;

  /**
   * Generate the reports for the simulation in this folder.
   */
  @Parameter(property = "gatling.reportsOnly", alias = "ro")
  private String reportsOnly;

  /**
   * Use this folder to discover simulations that could be run.
   */
  @Parameter(property = "gatling.simulationsFolder", alias = "sf", defaultValue = "${project.basedir}/src/test/scala")
  private File simulationsFolder;

  /**
   * A name of a Simulation class to run.
   */
  @Parameter(property = "gatling.simulationClass", alias = "sc")
  private String simulationClass;

  /**
   * Use this folder as the folder where feeders are stored.
   */
  @Parameter(property = "gatling.dataFolder", alias = "df", defaultValue = "${project.basedir}/src/test/resources/data")
  private File dataFolder;

  /**
   * Use this folder as the folder where results are stored.
   */
  @Parameter(property = "gatling.resultsFolder", alias = "rf", defaultValue = "${project.basedir}/target/gatling")
  private File resultsFolder;

  /**
   * Extra JVM arguments to pass when running Gatling.
   */
  @Parameter(property = "gatling.jvmArgs")
  private List<String> jvmArgs;

  /**
   * Extra JVM arguments to pass when running Zinc.
   */
  @Parameter(property = "gatling.zincJvmArgs")
  private List<String> zincJvmArgs;

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

  /**
   * Force the name of the directory generated for the results of the run.
   */
  @Parameter(property = "gatling.outputName", alias = "on")
  private String outputDirectoryBaseName;

  /**
   * Propagate System properties to forked processes.
   */
  @Parameter(property = "gatling.propagateSystemProperties", defaultValue = "true")
  private boolean propagateSystemProperties;

  /**
   * Disable the plugin.
   */
  @Parameter(property = "gatling.skip", defaultValue = "false")
  private boolean skip;

  /**
   * Disable the Scala compiler, if scala-maven-plugin is already in charge
   * of compiling the simulations.
   */
  @Parameter(property = "gatling.disableCompiler", defaultValue = "false")
  private boolean disableCompiler;

  /**
   * List of list of include patterns to use for scanning. Includes all simulations by default.
   */
  @Parameter(property = "gatling.includes")
  private String[] includes;

  /**
   * List of list of exclude patterns to use for scanning. By default empty.
   */
  @Parameter(property = "gatling.excludes")
  private String[] excludes;

  /**
   * Iterate over multiple simulations if more than one simulation file is found. By default false.
   * If multiple simulations are found but {@literal runMultipleSimulations} is false the execution will fail.
   */
  @Parameter(defaultValue = "false")
  private boolean runMultipleSimulations;

  /**
   * Override Gatling's default JVM args, instead of replacing them.
   */
  @Parameter(defaultValue = "false")
  private boolean overrideGatlingJvmArgs;

  /**
   * Override Zinc's default JVM args, instead of replacing them.
   */
  @Parameter(defaultValue = "false")
  private boolean overrideZincJvmArgs;

  @Parameter(defaultValue = "${plugin.artifacts}", readonly = true)
  private List<Artifact> artifacts;

  @Parameter(defaultValue = "${basedir}/target/gatling", readonly = true)
  private File reportsDirectory;

  @Parameter(property = "gatling.useOldJenkinsJUnitSupport", defaultValue = "false")
  private boolean useOldJenkinsJUnitSupport;

  /**
   * A short description of the run to include in the report.
   */
  @Parameter(property = "gatling.runDescription")
  private String runDescription;

  /**
   * TargetsIO: Name of product that is being tested.
   */
  @Parameter(property = "gatling.productName", alias = "pn", defaultValue = "ANONYMOUS_PRODUCT")
  private String productName;

  /**
   * TargetsIO: Name of performance dashboard for this test.
   */
  @Parameter(property = "gatling.dashboardName", alias = "dn", defaultValue = "ANONYMOUS_DASHBOARD")
  private String dashboardName;

  /**
   * TargetsIO: Test run id.
   */
  @Parameter(property = "gatling.testRunId", alias = "tid", defaultValue = "ANONYMOUS_TEST_ID")
  private String testRunId;

  /**
   * TargetsIO: Build results url to post the results of this load test.
   */
  @Parameter(property = "gatling.buildResultsUrl", alias = "url", defaultValue = "http://localhost")
  private String buildResultsUrl;

  /**
   * TargetsIO: Build results url to post the results of this load test.
   */
  @Parameter(property = "gatling.targetsIoUrl", alias = "iourl", defaultValue = "http://localhost:8123")
  private String targetsIoUrl;

  /**
   * TargetsIO: the release number of the product.
   */
  @Parameter(property = "gatling.productRelease", alias = "pr", defaultValue = "1.0.0-SNAPSHOT")
  private String productRelease;

  /**
   * TargetsIO: Rampup time in seconds.
   */
  @Parameter(property = "gatling.rampupTimeInSeconds", alias = "rt", defaultValue = "30")
  private String rampupTimeInSeconds;

  /**
   * TargetsIO: Parse the target.io test asserts and fail build it not ok.
   */
  @Parameter(property = "gatling.assertResultsEnabled", alias = "ar", defaultValue = "false")
  private boolean assertResultsEnabled;

  /**
   * TargetsIO: Enable calls to TargetsIO.
   */
  @Parameter(property = "gatling.targetsIoEnabled", alias = "tie", defaultValue = "false")
  private boolean targetsIoEnabled;


  /**
   * Executes Gatling simulations.
   */
  @Override
  public void execute() throws MojoExecutionException {
    if (skip) {
      getLog().info("Skipping gatling-maven-plugin");
      return;
    }
    final ScheduledExecutorService exec;
    final TargetsIoClient targetsIoClient = targetsIoEnabled
            ? createTargetsIoClient()
            : null;

    if (targetsIoEnabled) {
	    final int periodInSeconds = 30;
	    getLog().info(String.format("Calling targetsIO (%s) keep alive every %d seconds.", targetsIoUrl, periodInSeconds));
	    final TargetsIoClient.KeepAliveRunner keepAliveRunner = new TargetsIoClient.KeepAliveRunner(targetsIoClient);
	    exec = Executors.newSingleThreadScheduledExecutor();
	    exec.scheduleAtFixedRate(keepAliveRunner, 0, periodInSeconds, TimeUnit.SECONDS);
    }
    else {
      exec = null;
    }

    // Create results directories
    resultsFolder.mkdirs();
    try {
        List<String> testClasspath = buildTestClasspath();

        Toolchain toolchain = toolchainManager.getToolchainFromBuildContext("jdk", session);
      if (!disableCompiler) {
        executeCompiler(zincJvmArgs(), testClasspath, toolchain);
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
        throw new MojoExecutionException("Gatling failed.", e);
      } else {
        getLog().warn("There were some errors while running your simulation, but failOnError was set to false won't fail your build.", e);
      }
    } finally {
        copyJUnitReports();
        if (exec != null) {
          getLog().info("Shut down keep alive executor.");
          exec.shutdown();
        }
    }
    if (targetsIoEnabled) {
      targetsIoClient.callTargetsIoFor(TargetsIoClient.Action.End);
      if (assertResultsEnabled) {
        try {
          assertResultsTargetIO(targetsIoClient);
        } catch (IOException e) {
          throw new MojoExecutionException("TargetsIO assertions check failed. " + e.getMessage(), e);
        }
      }
      else {
        getLog().info("TargetsIO assertions disabled.");
      }
    }
  }

  private TargetsIoClient createTargetsIoClient() {
    TargetsIoClient client = new TargetsIoClient(productName, dashboardName, testRunId, buildResultsUrl, productRelease, rampupTimeInSeconds, targetsIoUrl);
    client.injectLogger(new TargetsIoClient.Logger() {
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
      public void debug(final String message) {
      	getLog().debug(message);
      }
    });
    return client;
  }

  /**
   * Call the target io assertions and let build fail when not OK
   * @throws MojoExecutionException when the assertion check fails or
   * a technical issue occurs
   * @param targetsIoClient call this client
   */
  private void assertResultsTargetIO(TargetsIoClient targetsIoClient) throws MojoExecutionException, IOException {
    final String assertions = targetsIoClient.callCheckAsserts();
    if (assertions == null) {
      throw new MojoExecutionException("TargetsIO assertions could not be checked, received null");
    }
    if (assertions.contains("false")) {
      throw new MojoExecutionException("One of TargetsIO assertions is false: " + assertions);
    }
    else {
      getLog().info("TargetsIO assertions are OK: " + assertions);
    }
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

  private void copyJUnitReports() throws MojoExecutionException {

    try {
      if (useOldJenkinsJUnitSupport) {
        File[] runDirectories = reportsDirectory.listFiles(File::isDirectory);

        for (File runDirectory: runDirectories) {
          File jsDir = new File(runDirectory, "js");
          if (jsDir.exists() && jsDir.isDirectory()) {
            File assertionFile = new File(jsDir, "assertions.xml");
            if (assertionFile.exists()) {
              File newAssertionFile = new File(reportsDirectory, "assertions-" + runDirectory.getName() + ".xml");
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
    List<String> completeGatlingJvmArgs = new ArrayList<>();
    if(jvmArgs != null) {
      completeGatlingJvmArgs.addAll(jvmArgs);
    }
    if (overrideGatlingJvmArgs) {
      completeGatlingJvmArgs.addAll(GATLING_JVM_ARGS);
    }
    return completeGatlingJvmArgs;
  }

  private List<String> zincJvmArgs() {
    List<String> completeZincJvmArgs = new ArrayList<>();
    if(zincJvmArgs != null) {
      completeZincJvmArgs.addAll(zincJvmArgs);
    }
    if (overrideZincJvmArgs) {
      completeZincJvmArgs.addAll(ZINC_JVM_ARGS);
    }
    return completeZincJvmArgs;
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
    args.addAll(asList("-df", dataFolder.getCanonicalPath(),
                       "-rf", resultsFolder.getCanonicalPath(),
                       "-bdf", bodiesFolder.getCanonicalPath(),
                       "-sf", simulationsFolder.getCanonicalPath(),
                       "-rd", runDescription));

    if (noReports) {
      args.add("-nr");
    }

    addToArgsIfNotNull(args, simulationClass, "s");
    addToArgsIfNotNull(args, reportsOnly, "ro");
    addToArgsIfNotNull(args, outputDirectoryBaseName, "on");

    return args;
  }

  private List<String> compilerArgs() throws Exception {
    List<String> args = new ArrayList<>();
    args.addAll(asList("-sf", simulationsFolder.getCanonicalPath()));
    args.addAll(asList("-bf", compiledClassesFolder.getCanonicalPath()));
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

        boolean isIncluded = includes.isEmpty() || includes.contains(className);
        boolean isExcluded =  excludes.contains(className);

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
    return scanner.getIncludedFiles();
  }

  private String pathToClassName(String path) {
    return path.substring(0, path.length() - ".class".length()).replace(File.separatorChar, '.');
  }

  private boolean isConcreteClass(Class<?> clazz) {
    return !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers());
  }
}
