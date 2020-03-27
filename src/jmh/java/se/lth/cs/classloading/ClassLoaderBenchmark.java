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
import java.util.Arrays;
import java.util.List;
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
        FileInputStream fs = new FileInputStream(benchmarkSpecPath.toFile());
        return loadBenchmarkSpec(fs);
    }

    // We have a state which contains the main class of the program
    @State(Scope.Benchmark)
    public static class BenchmarkState {

        public static Map<String, Object> benchmarksInfo;

        @Param("FOP")
        public String benchmarkIdentifier;

        Class classToLoad;
        Object[] arguments;
        Method main;

        @Param("NOTHING")
        private String fileName;

        public BenchmarkState() {}

        @Setup()
        public void doSetup() throws NoSuchMethodException, FileNotFoundException {
            benchmarksInfo = loadBenchmarkSpec(Paths.get(fileName));
            Object currentBenchmark0 = benchmarksInfo.get(benchmarkIdentifier);
            Map<String, Object> currentBenchmark = (Map<String, Object>) currentBenchmark0;
            String jarPath = (String) currentBenchmark.get("jar-path");
            String mainClass = (String) currentBenchmark.get("main-class");

            classToLoad = loadClassFromJar(jarPath, mainClass);
            main = classToLoad.getDeclaredMethod("main", String[].class);
            List<Object> args = (List<Object>) currentBenchmark.get("arguments");
            arguments = Arrays.copyOf(args.toArray(), args.size(), String[].class);
        }
    }

    @Benchmark
    public void runMain(BenchmarkState state) throws InvocationTargetException, IllegalAccessException {
        state.main.invoke(null, new String[][] {(String[]) state.arguments});
    }

    public static void main(String[] args) throws RunnerException, FileNotFoundException {

        Options opt = new OptionsBuilder()
                .include(ClassLoaderBenchmark.class.getSimpleName())
                .warmupIterations(5)
                .measurementIterations(5)
                .threads(4)
                .forks(1)
                .param("fileName", args[0])
                .shouldFailOnError(true)
                .build();

        new Runner(opt).run();
    }
}
