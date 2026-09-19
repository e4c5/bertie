package com.raditha.dedup.analysis;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative, name-based detection of observable side effects in a statement
 * sequence. The analyzer does not attempt full type resolution; it recognises
 * well-known JDK / framework APIs by scope and method name. Anything it cannot
 * classify is treated as pure, so the result is a lower bound on the real side
 * effects (see design doc, "Side Effect Detection").
 */
public class SideEffectAnalyzer {

    public enum Category {
        FILE_IO, CONSOLE_IO, NETWORK, DATABASE, EXTERNAL_API, NON_IDEMPOTENT
    }

    /**
     * A single detected side effect. {@code signature} is stable across duplicates that
     * perform the same operation (category + method name), so two sequences can be
     * compared by their ordered signature lists.
     */
    public record SideEffect(Category category, String signature, String snippet) {
    }

    private static final Set<String> CONSOLE_SCOPES = Set.of("System.out", "System.err", "System.in");

    private static final Pattern FILE_TYPES = Pattern.compile(
            "^(java\\.io\\.|java\\.nio\\.file\\.)?(File|Files|Path|Paths|FileInputStream|FileOutputStream|"
                    + "FileReader|FileWriter|RandomAccessFile|BufferedWriter|BufferedReader|PrintWriter|"
                    + "FileChannel|ObjectOutputStream|ObjectInputStream|Scanner)$");
    private static final Set<String> FILE_METHODS = Set.of(
            "write", "writeString", "writeBytes", "writeAllBytes", "readString", "readAllBytes", "readAllLines",
            "lines", "newBufferedReader", "newBufferedWriter", "newInputStream", "newOutputStream",
            "createFile", "createDirectory", "createDirectories", "createTempFile", "createTempDirectory",
            "delete", "deleteIfExists", "move", "copy", "mkdir", "mkdirs", "createNewFile", "renameTo",
            "setLastModified", "append", "println", "print", "printf", "flush", "close", "read", "readLine",
            "nextLine", "next", "nextInt");

    private static final Pattern NETWORK_TYPES = Pattern.compile(
            "^(java\\.net\\.|java\\.net\\.http\\.)?(Socket|ServerSocket|DatagramSocket|URL|URLConnection|"
                    + "HttpURLConnection|HttpClient|HttpRequest|WebSocket|Channel|SocketChannel)$");
    private static final Set<String> NETWORK_METHODS = Set.of(
            "openConnection", "openStream", "connect", "send", "sendAsync", "accept", "bind", "getInputStream",
            "getOutputStream", "receive");

    private static final Pattern DATABASE_TYPES = Pattern.compile(
            "^(java\\.sql\\.|javax\\.persistence\\.|jakarta\\.persistence\\.)?(Connection|Statement|"
                    + "PreparedStatement|CallableStatement|DataSource|EntityManager|Session|JdbcTemplate|"
                    + "NamedParameterJdbcTemplate|Query|TypedQuery)$");
    private static final Set<String> DATABASE_METHODS = Set.of(
            "executeQuery", "executeUpdate", "execute", "executeBatch", "executeLargeUpdate", "prepareStatement",
            "createStatement", "commit", "rollback", "persist", "merge", "remove", "flush", "createQuery",
            "createNativeQuery", "getResultList", "getSingleResult", "update", "query", "queryForObject",
            "queryForList", "batchUpdate", "save", "saveAll", "saveAndFlush", "delete", "deleteAll", "deleteById",
            "findById", "findAll", "getConnection");
    private static final Pattern REPOSITORY_SCOPE = Pattern.compile(".*(Repository|Repo|Dao|DAO|Mapper)$");

    private static final Pattern EXTERNAL_API_TYPES = Pattern.compile(
            "^(RestTemplate|WebClient|RestClient|OkHttpClient|CloseableHttpClient|HttpClient|Retrofit|"
                    + "KafkaTemplate|RabbitTemplate|JmsTemplate|AmazonS3|S3Client|SqsClient|SnsClient|"
                    + "MessageProducer|Producer|Publisher)$");
    private static final Pattern EXTERNAL_API_SCOPE = Pattern.compile(".*(Client|Template|Gateway|Producer|Publisher)$");
    private static final Set<String> EXTERNAL_API_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "exchange", "put", "delete",
            "patchForObject", "execute", "send", "sendDefault", "convertAndSend", "publish", "putObject",
            "getObject", "deleteObject", "sendMessage", "get", "post", "retrieve", "newCall", "enqueue");

    private static final Set<String> NON_IDEMPOTENT_CALLS = Set.of(
            "System.currentTimeMillis", "System.nanoTime", "UUID.randomUUID", "Math.random",
            "Instant.now", "LocalDate.now", "LocalDateTime.now", "LocalTime.now", "ZonedDateTime.now",
            "OffsetDateTime.now", "Clock.systemUTC", "Clock.systemDefaultZone", "Thread.sleep",
            "System.exit", "Runtime.getRuntime", "System.gc", "Runtime.exec");
    private static final Set<String> RANDOM_METHODS = Set.of(
            "nextInt", "nextLong", "nextDouble", "nextFloat", "nextBoolean", "nextBytes", "nextGaussian", "ints",
            "longs", "doubles");
    private static final Pattern RANDOM_TYPES = Pattern.compile("^(Random|SecureRandom|ThreadLocalRandom|SplittableRandom)$");

    /**
     * Detect side effects in statement order.
     */
    public List<SideEffect> analyze(StatementSequence sequence) {
        List<SideEffect> effects = new ArrayList<>();
        if (sequence == null || sequence.statements() == null) {
            return effects;
        }
        for (Statement stmt : sequence.statements()) {
            for (MethodCallExpr call : stmt.findAll(MethodCallExpr.class)) {
                SideEffect effect = classify(call);
                if (effect != null) {
                    effects.add(effect);
                }
            }
            for (ObjectCreationExpr creation : stmt.findAll(ObjectCreationExpr.class)) {
                SideEffect effect = classify(creation);
                if (effect != null) {
                    effects.add(effect);
                }
            }
        }
        return effects;
    }

    private SideEffect classify(ObjectCreationExpr creation) {
        String typeName = creation.getType().getNameAsString();
        if (FILE_TYPES.matcher(typeName).matches() && !typeName.equals("File") && !typeName.equals("Path")) {
            return new SideEffect(Category.FILE_IO, "new " + typeName, creation.toString());
        }
        if (NETWORK_TYPES.matcher(typeName).matches() && !typeName.equals("URL")) {
            return new SideEffect(Category.NETWORK, "new " + typeName, creation.toString());
        }
        return null;
    }

    private SideEffect classify(MethodCallExpr call) {
        String name = call.getNameAsString();
        String scopeText = call.getScope().map(Expression::toString).orElse("");
        String scopeType = scopeTypeName(call.getScope().orElse(null));
        String qualified = scopeText.isEmpty() ? name : scopeText + "." + name;

        if (CONSOLE_SCOPES.contains(scopeText)) {
            return new SideEffect(Category.CONSOLE_IO, qualified, call.toString());
        }
        if (NON_IDEMPOTENT_CALLS.contains(qualified)
                || (RANDOM_METHODS.contains(name) && RANDOM_TYPES.matcher(scopeType).matches())) {
            return new SideEffect(Category.NON_IDEMPOTENT, qualified, call.toString());
        }
        if (DATABASE_TYPES.matcher(scopeType).matches() && DATABASE_METHODS.contains(name)
                || REPOSITORY_SCOPE.matcher(scopeType).matches() && DATABASE_METHODS.contains(name)) {
            return new SideEffect(Category.DATABASE, "db." + name, call.toString());
        }
        if ((EXTERNAL_API_TYPES.matcher(scopeType).matches() || EXTERNAL_API_SCOPE.matcher(scopeType).matches())
                && EXTERNAL_API_METHODS.contains(name)) {
            return new SideEffect(Category.EXTERNAL_API, "api." + name, call.toString());
        }
        if (NETWORK_TYPES.matcher(scopeType).matches() && NETWORK_METHODS.contains(name)) {
            return new SideEffect(Category.NETWORK, "net." + name, call.toString());
        }
        if (FILE_TYPES.matcher(scopeType).matches() && FILE_METHODS.contains(name)) {
            return new SideEffect(Category.FILE_IO, "file." + name, call.toString());
        }
        return null;
    }

    /**
     * Best-effort name of the scope's type: a static reference ({@code Files.write}) yields
     * the class name, a constructor scope yields the created type, and a variable is looked
     * up in its declaring callable/class when possible; otherwise the variable name itself
     * is returned so suffix heuristics (e.g. {@code *Repository}) can still apply.
     */
    private String scopeTypeName(Expression scope) {
        if (scope == null) {
            return "";
        }
        if (scope.isObjectCreationExpr()) {
            return scope.asObjectCreationExpr().getType().getNameAsString();
        }
        if (scope.isMethodCallExpr()) {
            return scope.asMethodCallExpr().getNameAsString();
        }
        String simpleName;
        if (scope.isNameExpr()) {
            simpleName = scope.asNameExpr().getNameAsString();
        } else if (scope.isFieldAccessExpr()) {
            simpleName = scope.asFieldAccessExpr().getNameAsString();
        } else {
            return scope.toString();
        }
        if (!simpleName.isEmpty() && Character.isUpperCase(simpleName.charAt(0))) {
            return simpleName;
        }
        String declared = declaredTypeOf(scope, simpleName);
        return declared != null ? declared : capitalize(simpleName);
    }

    private String declaredTypeOf(Expression scope, String varName) {
        Optional<CallableDeclaration<?>> callable = scope.findAncestor(CallableDeclaration.class)
                .map(c -> (CallableDeclaration<?>) c);
        if (callable.isPresent()) {
            for (Parameter param : callable.get().getParameters()) {
                if (param.getNameAsString().equals(varName)) {
                    return erase(param.getType().asString());
                }
            }
            Optional<VariableDeclarator> local = callable.get().findFirst(VariableDeclarator.class,
                    v -> v.getNameAsString().equals(varName));
            if (local.isPresent()) {
                return erase(local.get().getType().asString());
            }
        }
        Optional<ClassOrInterfaceDeclaration> clazz = scope.findAncestor(ClassOrInterfaceDeclaration.class);
        if (clazz.isPresent()) {
            for (var field : clazz.get().getFields()) {
                for (var v : field.getVariables()) {
                    if (v.getNameAsString().equals(varName)) {
                        return erase(field.getElementType().asString());
                    }
                }
            }
        }
        return null;
    }

    private static String erase(String type) {
        int lt = type.indexOf('<');
        String erased = lt > 0 ? type.substring(0, lt) : type;
        int dot = erased.lastIndexOf('.');
        return dot >= 0 ? erased.substring(dot + 1) : erased;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
