// This is a benchmark for running external programs as benchmarks.
// We do this by loading the jar dynamically and then run the main class
// of the benchmark.
package se.lth.cs.classloading;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ClassLoaderBenchmark {

    /**
     * A method for loading a JAR and it's main class given a path and the class to load
     * @param jarPath the path of the JAR you want to load
     * @param mainClass the class with the main function
     * @return
     * @throws MalformedURLException
     * @throws ClassNotFoundException
     */
    public static Class loadClassFromJar(String jarPath, String mainClass) throws FileNotFoundException {
        Path jar = validatePath(jarPath);

        URLClassLoader loader = null;
        try {
            loader = new URLClassLoader(
                new URL[] { jar.toUri().toURL() },
                    ClassLoader.getSystemClassLoader()
            );
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }

        Class classFromJar = null;
        try {
            classFromJar = Class.forName(mainClass, true, loader);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return classFromJar;
    }


    /**
     * A function that interrupts execution if the path does not exist
     * if it works, the absolute path is returned.
     * @param path
     * @return
     * @throws FileNotFoundException
     */
    private static Path validatePath(String path) throws FileNotFoundException {
        if (path == null) {
            throw new IllegalArgumentException();
        }
        Path absolutePath = Paths.get(path).toAbsolutePath();

        if (!absolutePath.toFile().exists()) {
            throw new FileNotFoundException(path);
        }
        return absolutePath;
    }

    /**
     * Function loading a benchmark specification from a JSON file
     * @param stream of the file
     * @returns an object describing the specification
     */
    private static JSONObject loadBenchmarkSpec(InputStream stream) {
        JSONTokener tokener = new JSONTokener(stream);
        JSONObject specification = new JSONObject(tokener);
        return specification;
    }

    private static JSONObject loadBenchmarkSpec(Path benchmarkSpecPath) throws FileNotFoundException {
        FileInputStream fs = new FileInputStream(benchmarkSpecPath.toFile());
        return loadBenchmarkSpec(fs);
    }

    private static class RunSpecRow {
        public String classPath;
        public String mainClass;
        public List<String> arguments;
        public List<String> jvmArgs;
    }

    /**
     * Converts a benchmark specification to a specification about what JMH should run.
     * @param benchmarkSpec
     * @return
     */
    private static Map<String, RunSpecRow> benchmarkSpecToRuns(JSONObject benchmarkSpec) {
        Map<String, RunSpecRow> runSpec = new HashMap<>();

        for (String program : benchmarkSpec.keySet()) {
            JSONObject programData = benchmarkSpec.getJSONObject(program);
            String mainClass = programData.getString("main-class");
            JSONObject variantsData = programData.getJSONObject("variants");
            JSONArray argumentsData  = programData.getJSONArray("arguments");
            // argumentsData can be parameters to JMH, all combinations should be tried.
            for (String variant : variantsData.keySet()) {
                JSONObject variantInfo = variantsData.getJSONObject(variant);
                JSONArray jvmArgs = variantInfo.getJSONArray("jvm-args");
                String classPath = variantInfo.getString("classpath");

                // I think the classpath and the jvm args are both
                // Arguments to the JVM anyway

                // We add this variant specific data to the map.
                RunSpecRow row = new RunSpecRow();
                row.mainClass = mainClass; // To be passed as parameter

                row.arguments = joinArguments(extractArguments(argumentsData)); // Cannot pass arrays as lists of parameters, needs to format them

                List<String> jvmArgsList = new ArrayList<>();
                for (int i = 0; i < jvmArgs.length(); ++i) { jvmArgsList.add(jvmArgs.getString(i)); }
                row.jvmArgs = jvmArgsList;

                row.classPath = classPath;

                runSpec.put(variant, row);
            }
        }
        return runSpec;
    }

    private static List<List<String>> extractArguments(JSONArray arr) {
        List<List<String>> result = new ArrayList<>();

        // Java like its 1999
        for (int i = 0; i < arr.length(); i++) {
            JSONArray a = arr.getJSONArray(i);
            result.add(new ArrayList());
            for (int j = 0; j < a.length(); ++j) {
                result.get(i).add(a.getString(j));
            }
        }

        return result;
    }

    private static List<String> joinArguments(List<List<String>> args) {
        List<String> result = new ArrayList<>();

        for (List<String> l : args) {
            String s = String.join(" ", l);
            result.add(s);
        }

        return result;
    }

    private static List<Options> createOptions(Map<String, RunSpecRow> runSpec) {
        List<Options> options = new ArrayList<>();

        for (String variant : runSpec.keySet()) {
            ChainedOptionsBuilder optionsBuilder = new OptionsBuilder()
                    .include(ClassLoaderBenchmark.class.getSimpleName())
                    .mode(Mode.SingleShotTime)
                    .warmupIterations(2)
                    .measurementIterations(3)
                    .threads(1)
                    .forks(1)
                    .shouldFailOnError(true);

            RunSpecRow data = runSpec.get(variant);
            optionsBuilder.param("benchmarkIdentifier", variant);
            optionsBuilder.param("mainClass", data.mainClass);
            optionsBuilder.param("classPath", data.classPath);

            String[] argsArray = new String[data.arguments.size()];
            data.arguments.toArray(argsArray);
            optionsBuilder.param("arguments", argsArray);

            String[] jvmArgsArray = new String[data.jvmArgs.size()];
            data.jvmArgs.toArray(jvmArgsArray);
            optionsBuilder.jvmArgs(jvmArgsArray);

            options.add(optionsBuilder.build());
        }

        return options;
    }

    // We have a state which contains the main class of the program
    @State(Scope.Benchmark)
    public static class BenchmarkState {

        @Param("NOTHING")
        public String benchmarkIdentifier;

        @Param("NO-MAIN")
        public String mainClass;

        @Param("NO-CLASSPATH")
        public String classPath;

        @Param("NONE")
        public String arguments;

        Object[] argumentObjs;
        Method mainMethod;

        public BenchmarkState() {}

        @Setup()
        public void doSetup() throws NoSuchMethodException, FileNotFoundException, ClassNotFoundException {
            Class mainC = loadClassFromJar(classPath, mainClass);
            mainMethod = mainC.getDeclaredMethod("main", String[].class);
            argumentObjs = arguments.split(" ");
        }
    }

    @Benchmark
    public void runMain(BenchmarkState state) throws InvocationTargetException, IllegalAccessException {
        state.mainMethod.invoke(null, new String[][] {(String[]) state.argumentObjs});
    }

    public static void main(String[] args) throws RunnerException, FileNotFoundException {

        List<Options> opts = createOptions(benchmarkSpecToRuns(loadBenchmarkSpec(Paths.get(args[0]))));

        for (Options o : opts) {
            new Runner(o).run();
        }
    }
}
