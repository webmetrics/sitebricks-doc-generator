package biz.neustar.webmetrics.maven.plugins;

import com.thoughtworks.qdox.JavaDocBuilder;
import com.thoughtworks.qdox.model.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Generates documentation for Sitebricks API services.
 *
 * @goal generate-doc
 * @requiresProject true
 * @phase site
 */
public class ApiDocGeneratorMojo extends AbstractMojo {

    private Map<String, String> httpVerbs = new HashMap<String, String>();

    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The java source directory that will be scanned for Sitebricks API services.
     *
     * @parameter expression="\${project.build.sourceDirectory}
     */
    private File sourceDirectory;

    /**
     * The build directory.
     * <p>
     *     The generated documentation is stored in files here.
     * </p>
     * @parameter expression="\${project.build.directory}
     */
    private File buildDirectory;

    private File docsDirectory;

    public void execute() throws MojoExecutionException, MojoFailureException {

        try {
            docsDirectory = new File(buildDirectory, "/sitebricks-docs");
            if( ! docsDirectory.exists() )
                docsDirectory.mkdirs();
            
            httpVerbs.put("com.google.sitebricks.http.Get", "GET");
            httpVerbs.put("com.google.sitebricks.http.Post", "POST");
            httpVerbs.put("com.google.sitebricks.http.Delete", "DELETE");
            httpVerbs.put("com.google.sitebricks.http.Put", "PUT");
            httpVerbs.put("com.google.sitebricks.http.Head", "HEAD");
            httpVerbs.put("com.google.sitebricks.http.Select", "SELECT");
            httpVerbs.put("com.google.sitebricks.http.Trace", "TRACE");

            getLog().info("Generating API Documentation for Sitebricks @Services");
            getLog().info("Directory to scan: " + sourceDirectory.getAbsolutePath());

            JavaDocBuilder builder = new JavaDocBuilder();
            builder.addSourceTree(sourceDirectory);
            for (JavaSource source : builder.getSources()) {
                for (JavaClass javaClass : source.getClasses()) {
                    String baseUrl = null;
                    boolean isService = false;
                    for (Annotation annotation : javaClass.getAnnotations()) {
                        if (annotation.getType().getFullyQualifiedName().equals("com.google.sitebricks.headless.Service")) {
                            getLog().debug("Found @Service annotation on class " + javaClass.getFullyQualifiedName());
                            isService = true;
                        }

                        if (annotation.getType().getFullyQualifiedName().equals("com.google.sitebricks.At")) {
                            getLog().debug("Found @At annotation on class " + javaClass.getFullyQualifiedName());
                            baseUrl = annotation.getNamedParameter("value").toString().replaceAll("\"", "");
                        }
                    }

                    if (isService && baseUrl != null) {
                        parseClass(javaClass, baseUrl);
                    }
                }
            }
        }
        catch (IOException e) {
            throw new MojoExecutionException("Unable to write documentation file", e);
        }
    }

    private void parseClass(JavaClass javaClass, String baseUrl) throws IOException, MojoFailureException {

        File outputFile = new File(docsDirectory, "/" + javaClass.getFullyQualifiedName() + ".txt");
        FileWriter writer = new FileWriter(outputFile);

        try{
            getLog().info("Parsing class " + javaClass.getFullyQualifiedName());

            for (JavaMethod method : javaClass.getMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    String verb = httpVerbs.get(annotation.getType().getFullyQualifiedName());
                    if (verb != null) {
                        parseMethod(method, baseUrl, verb, writer);
                    }
                }
            }
        }
        finally{
            if( writer != null )
                writer.close();
        }
    }

    private void parseMethod(JavaMethod method, String baseUrl, String verb, FileWriter writer) throws IOException, MojoFailureException {

        getLog().info("Parsing method " + method.getName());

        String url = baseUrl;
        Object example = null;

        // does the method have an optional @At?
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation.getType().getFullyQualifiedName().equals("com.google.sitebricks.At")) {
                String extraUrl = annotation.getNamedParameter("value").toString().replaceAll("\"", "");
                url = url + extraUrl;
            } else if (annotation.getType().getFullyQualifiedName().equals(ExpectedPayload.class.getName())) {
                try {
                    Class<?> clazz = Class.forName(annotation.getProperty("value").getParameterValue().toString().replaceAll("\\.class", ""));
                    Method examplePayloadMethod = clazz.getMethod("examplePayload");
                    example = examplePayloadMethod.invoke(null);
                } catch (ClassNotFoundException e) {
                    getLog().error(e);
                    throw new MojoFailureException("Unable to load ExpectedPayload class by name");
                } catch (NoSuchMethodException e) {
                    getLog().error(e);
                    throw new MojoFailureException("Unable to load ExpectedPayload class by name");
                } catch (InvocationTargetException e) {
                    getLog().error(e);
                    throw new MojoFailureException("Unable to load ExpectedPayload class by name");
                } catch (IllegalAccessException e) {
                    getLog().error(e);
                    throw new MojoFailureException("Unable to load ExpectedPayload class by name");
                }
            }
        }

        writer.write("\n\n================================================================\n");
        writer.write(verb + " " + url);
        writer.write("\n\n");

        String comment = method.getComment();

        if( comment != null )
            writer.write(comment);

        DocletTag[] paramTags = method.getTagsByName("param", false);
        if (paramTags.length > 0) {
            writer.write("\nURL fields:\n");
            for (DocletTag paramTag : paramTags) {
                writer.write(" - " + paramTag.getValue());
            }
        }

        DocletTag[] queryTags = method.getTagsByName("query", false);
        if (queryTags.length > 0) {
            writer.write("\nQuery parameters:\n");
            for (DocletTag queryTag : queryTags) {
                writer.write(" - " + queryTag.getValue());
            }
        }

        if (example != null) {
            writer.write("\n");
            objectMapper.defaultPrettyPrintingWriter().writeValue(writer, example);
        }
    }
}
