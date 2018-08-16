package com.github.havardh.javaflow.plugins;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.github.havardh.javaflow.Execution;
import com.github.havardh.javaflow.model.TypeMap;
import com.github.havardh.javaflow.phases.filetransform.CommentPrependTransformer;
import com.github.havardh.javaflow.phases.filetransform.EslintDisableTransformer;
import com.github.havardh.javaflow.phases.parser.java.JavaParser;
import com.github.havardh.javaflow.phases.reader.FileReader;
import com.github.havardh.javaflow.phases.transform.InheritanceTransformer;
import com.github.havardh.javaflow.phases.transform.SortedTypeTransformer;
import com.github.havardh.javaflow.phases.verifier.ClassGetterNamingVerifier;
import com.github.havardh.javaflow.phases.verifier.MemberFieldsPresentVerifier;
import com.github.havardh.javaflow.phases.verifier.Verifier;
import com.github.havardh.javaflow.phases.writer.flow.FlowWriter;
import com.github.havardh.javaflow.phases.writer.flow.converter.Converter;
import com.github.havardh.javaflow.phases.writer.flow.converter.JavaFlowConverter;
import com.github.havardh.javaflow.plugins.exceptions.PackageDirectoryNotFound;
import com.github.havardh.javaflow.util.App;

/**
 * Generates flow types for a set of java models
 *
 * @author Håvard Wormdal Høiby
 */
@Mojo(name = "build")
public class JavaflowMojo extends AbstractMojo {

  /**
   * base output directory
   */
  @Parameter(property = "targetDirectory", defaultValue = "${basedir}/target")
  private String targetDirectory;

  /**
   * base source directory
   */
  @Parameter(property = "sourceDirectory", defaultValue = "${basedir}/src/main/java")
  private String sourceDirectory;

  /**
   * list of apis to generate types for
   */
  @Parameter(property = "apis")
  private List<Api> apis;


  /** {@inheritDoc} */
  public void execute() throws MojoExecutionException {
    try {
      apis.forEach(this::run);
    } catch (Exception e) {
      throw new MojoExecutionException("Could not generate types\n\n", e);
    }
  }

  /**
   * Runs converts the input {@api} to a flowtype file at the specified location
   *
   * @param api - the api to generate flowtypes for
   */
  private void run(Api api) {
    Collection<File> files = getModelFiles(api);

    TypeMap typeMap = api.getTypes() == null ? TypeMap.emptyTypeMap() : new TypeMap(api.getTypes());

    Converter converter = new JavaFlowConverter(typeMap);

    Execution execution = new Execution(
        new FileReader(),
        new JavaParser(),
        asList(
            new InheritanceTransformer(),
            new SortedTypeTransformer()
        ),
        getAllVerifiers(api.getVerifications(), typeMap),
        new FlowWriter(converter),
        asList(
            new CommentPrependTransformer("Generated by javaflow " + App.version()),
            new EslintDisableTransformer(singletonList("no-use-before-define")),
            new CommentPrependTransformer("@flow")
        )
    );

    String flow = execution.run(
        files.stream().map(file -> {
          try {
            return file.getCanonicalPath();
          } catch (IOException e) {
            getLog().error(e);
            return "";
          }
        }).collect(Collectors.toList())
            .toArray(new String[]{})
    );

    try {
      prepareTargetDirectory(targetDirectory);
      String outputFile = targetDirectory + "/" + api.getOutput();
      Files.write(Paths.get(outputFile), asList(flow.split("\n")));
      getLog().info(format("Wrote %d types to %s.", files.size(), outputFile));
    } catch (IOException e) {
      getLog().error(e);
    }
  }

  private Collection<File> getModelFiles(Api api) {
    String baseSourceDirectory = sourceDirectory + "/" + api.getPackageName().replace('.', '/');

    File baseDirectoryFile = new File(baseSourceDirectory);

    if (!baseDirectoryFile.isDirectory()) {
      throw new PackageDirectoryNotFound(api.getPackageName(), baseSourceDirectory);
    }

    return FileUtils.listFiles(
        new File(baseSourceDirectory),
        new SuffixFileFilter(api.getSuffixes().toArray(new String[]{})),
        TrueFileFilter.INSTANCE
    );
  }

  private List<Verifier> getAllVerifiers(Map<String, Boolean> verifiersConfig, TypeMap typeMap) {
    List<Verifier> verifiers = new ArrayList<>();
    verifiers.add(new MemberFieldsPresentVerifier(typeMap));
    verifiers.addAll(getOptionalVerifiers(verifiersConfig));
    return verifiers;
  }

  private List<Verifier> getOptionalVerifiers(Map<String, Boolean> verifiersConfig) {
    List<Verifier> verifiers = new ArrayList<>();

    if (verifiersConfig.getOrDefault("verifyGetters", false)) {
      verifiers.add(new ClassGetterNamingVerifier());
    }

    return verifiers;
  }

  private void prepareTargetDirectory(String targetDirectory) throws IOException {
    Path outputPath = Paths.get(targetDirectory);
    Files.createDirectories(outputPath);
  }
}
