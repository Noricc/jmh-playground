// This is a benchmark for running external programs as benchmarks.
// We do this by loading the jar dynamically and then run the main class
// of the benchmark.
package se.lth.cs.classloading;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
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
import java.util.Map;

public class ClassLoaderBenchmark {

    /**
     * A method for loading a JAR and it's main class given a path and the class to load
     * @param jarPath the path of the JAR you want to load
     * @param mainClass the class with the main function
     * @return
     * @throws MalformedURLException
     * @throws ClassNotFoundException
     */
    private static Class loadClassFromJar(String jarPath, String mainClass) throws FileNotFoundException {
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

    private static Path validatePath(String jarPath) throws FileNotFoundException {
        if (jarPath == null) {
            throw new IllegalArgumentException();
        }
        Path jar = Paths.get(jarPath).toAbsolutePath();

        if (!jar.toFile().exists()) {
            throw new FileNotFoundException(jarPath);
        }
        return jar;
    }

    private static Map<String, Object> loadBenchmarkSpec(InputStream stream) {
        JSONTokener tokener = new JSONTokener(stream);
        JSONObject specification = new JSONObject(tokener);
        return specification.toMap();
    }

    private static Map<String, Object> loadBenchmarkSpec(Path benchmarkSpecPath) throws FileNotFoundException {
        FileInputStream fs = null;
        fs = new FileInputStream(benchmarkSpecPath.toFile());
        return loadBenchmarkSpec(fs);
    }

    public static Map<String, Object> benchmarksInfo;

    // We have a state which contains the main class of the program
    @State(Scope.Benchmark)
    public static class BenchmarkState {

        @Param("FOP")
        public String benchmarkIdentifier;

        public Map<String, Object> benchmarksInfo;

        Class classToLoad;
        String[] arguments = new String[] {} ;
        Method main;

        public BenchmarkState() {
        }

        @Setup()
        public void doSetup() throws NoSuchMethodException, FileNotFoundException {
            Map<String, String> currentBenchmark = (Map<String, String>) benchmarksInfo.get(benchmarkIdentifier);
            String jarPath = currentBenchmark.get("jar-path");
            String mainClass = currentBenchmark.get("main-class");
            classToLoad = loadClassFromJar(jarPath, mainClass);
            main = classToLoad.getDeclaredMethod("main", String[].class);
        }
    }

    @Benchmark
    public void runMain(BenchmarkState state) throws InvocationTargetException, IllegalAccessException {
        state.main.invoke(null, (Object) state.arguments);
    }

    public static void main(String[] args) throws RunnerException, FileNotFoundException {

        benchmarksInfo = loadBenchmarkSpec(Paths.get(args[0]));

        Options opt = new OptionsBuilder()
                .include(ClassLoaderBenchmark.class.getSimpleName())
                .warmupIterations(5)
                .measurementIterations(5)
                .threads(4)
                .forks(1)
                .shouldFailOnError(true)
                .build();

        new Runner(opt).run();
    }
}
