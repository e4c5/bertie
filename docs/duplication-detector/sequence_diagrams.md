# Duplication Detector - Sequence Diagrams

**Version**: 1.0  
**Date**: December 9, 2025  
**Purpose**: Visual representation of essential flows in the duplication detection system

---

## Table of Contents

1. [Single File Analysis Flow](#1-single-file-analysis-flow)
2. [Project-Wide Analysis Flow](#2-project-wide-analysis-flow)
3. [Similarity Calculation Flow](#3-similarity-calculation-flow)
4. [Variation Tracking Flow](#4-variation-tracking-flow)
5. [Refactoring Recommendation Flow](#5-refactoring-recommendation-flow)
6. [Pre-Filtering Flow](#6-pre-filtering-flow)

---

## 1. Single File Analysis Flow

This diagram shows how a single Java file is analyzed for duplicate code blocks.

```mermaid
sequenceDiagram
    actor User
    participant CLI as DuplicationDetectorCLI
    participant Analyzer as DuplicationAnalyzer
    participant Compiler as AbstractCompiler
    participant Extractor as StatementExtractor
    participant Similarity as SimilarityCalculator
    participant Clusterer as DuplicateClusterer
    participant Reporter as ReportGenerator

    User->>CLI: analyzeFile(sourceFile)
    CLI->>Analyzer: analyzeFile(sourceFile)
    
    activate Analyzer
    Analyzer->>Compiler: new AbstractCompiler(sourceFile)
    Compiler-->>Analyzer: compiler instance
    
    Analyzer->>Compiler: getCompilationUnit()
    Compiler-->>Analyzer: CompilationUnit
    
    Analyzer->>Extractor: extract(cu, sourceFile)
    activate Extractor
    Extractor->>Extractor: visitAllMethods()
    Extractor->>Extractor: slidingWindow(minLines)
    Extractor-->>Analyzer: List<StatementSequence>
    deactivate Extractor
    
    Analyzer->>Analyzer: findDuplicates(sequences)
    activate Analyzer
    Analyzer->>Analyzer: generateCandidates(sequences)
    
    loop For each candidate pair
        Analyzer->>Similarity: compare(seq1, seq2)
        activate Similarity
        Similarity->>Similarity: calculateLCS()
        Similarity->>Similarity: calculateLevenshtein()
        Similarity->>Similarity: calculateStructural()
        Similarity-->>Analyzer: SimilarityResult
        deactivate Similarity
    end
    
    Analyzer->>Clusterer: cluster(results)
    activate Clusterer
    Clusterer->>Clusterer: groupBySimilarity()
    Clusterer-->>Analyzer: List<DuplicateCluster>
    deactivate Clusterer
    deactivate Analyzer
    
    Analyzer-->>CLI: DuplicationReport
    deactivate Analyzer
    
    CLI->>Reporter: generate(report, format)
    Reporter-->>CLI: formatted output
    CLI-->>User: report (text/JSON)
```

**Key Points**:
- Leverages `AbstractCompiler` for parsing (no reinvention)
- Sliding window extracts all possible sequences
- Parallel similarity calculation for performance
- Clustering groups related duplicates

---

## 2. Project-Wide Analysis Flow

This diagram shows cross-file duplicate detection across an entire project.

```mermaid
sequenceDiagram
    actor User
    participant CLI as DuplicationDetectorCLI
    participant Analyzer as DuplicationAnalyzer
    participant Settings as Settings
    participant FileSystem as Files
    participant PreFilter as PreFilterChain
    participant Clusterer as DuplicateClusterer

    User->>CLI: analyzeProject()
    CLI->>Analyzer: analyzeProject()
    
    activate Analyzer
    Analyzer->>Settings: getInstance()
    Settings-->>Analyzer: settings
    
    Analyzer->>Settings: getBasePath()
    Settings-->>Analyzer: basePath
    
    Analyzer->>FileSystem: walk(basePath)
    FileSystem-->>Analyzer: Stream<Path>
    
    Analyzer->>Analyzer: filter(.java, exclude target/generated)
    Analyzer-->>Analyzer: List<Path> javaFiles
    
    loop For each Java file
        Analyzer->>Analyzer: analyzeFile(file)
        Note right of Analyzer: See Single File<br/>Analysis Flow
        Analyzer-->>Analyzer: file clusters
    end
    
    Analyzer->>Analyzer: allClusters.addAll()
    
    opt Cross-file clustering
        Analyzer->>PreFilter: filterCrossPairs(allClusters)
        activate PreFilter
        PreFilter->>PreFilter: sizeFilter()
        PreFilter->>PreFilter: structuralFilter()
        PreFilter->>PreFilter: lshBucketingFilter()
        PreFilter-->>Analyzer: filtered candidates
        deactivate PreFilter
        
        Analyzer->>Clusterer: clusterAll(allClusters)
        activate Clusterer
        Clusterer->>Clusterer: mergeSimilarClusters()
        Clusterer-->>Analyzer: merged clusters
        deactivate Clusterer
    end
    
    Analyzer-->>CLI: DuplicationReport
    deactivate Analyzer
    
    CLI-->>User: consolidated report
```

**Key Points**:
- Uses `Settings` for project configuration
- Filters generated code automatically
- Pre-filtering reduces O(N²) comparisons dramatically
- Cross-file clustering identifies duplicates across modules

---

## 3. Similarity Calculation Flow

This diagram details how two statement sequences are compared for similarity.

```mermaid
sequenceDiagram
    participant Analyzer as DuplicationAnalyzer
    participant Calc as SimilarityCalculator
    participant Normalizer as TokenNormalizer
    participant LCS as LCSSimilarity
    participant Lev as LevenshteinSimilarity
    participant Struct as StructuralSimilarity
    participant VarTrack as VariationTracker

    Analyzer->>Calc: compare(seq1, seq2)
    activate Calc
    
    Calc->>Normalizer: normalize(seq1, seq2)
    activate Normalizer
    
    loop For each statement in seq1
        Normalizer->>Normalizer: tokenize(statement)
        Normalizer->>Normalizer: normalizeToken()
        Note right of Normalizer: VAR, METHOD_CALL(save)<br/>INT_LIT, TYPE(User)
    end
    
    loop For each statement in seq2
        Normalizer->>Normalizer: tokenize(statement)
        Normalizer->>Normalizer: normalizeToken()
    end
    
    Normalizer->>VarTrack: trackVariations(tokens1, tokens2)
    activate VarTrack
    VarTrack->>VarTrack: alignTokens()
    VarTrack->>VarTrack: findDifferences()
    VarTrack-->>Normalizer: VariationAnalysis
    deactivate VarTrack
    
    Normalizer-->>Calc: tokens1, tokens2, variations
    deactivate Normalizer
    
    par Parallel Similarity Calculation
        Calc->>LCS: calculate(tokens1, tokens2)
        activate LCS
        LCS->>LCS: computeLCS_DP()
        LCS-->>Calc: lcsScore
        deactivate LCS
    and
        Calc->>Lev: calculate(tokens1, tokens2)
        activate Lev
        Lev->>Lev: computeDistance_DP()
        Lev-->>Calc: levenshteinScore
        deactivate Lev
    and
        Calc->>Struct: calculate(seq1, seq2)
        activate Struct
        Struct->>Struct: extractStructuralSignature()
        Struct->>Struct: jaccardSimilarity(patterns)
        Struct-->>Calc: structuralScore
        deactivate Struct
    end
    
    Calc->>Calc: combineScores(lcs, lev, struct)
    Note right of Calc: 0.40×LCS + 0.40×Lev<br/>+ 0.20×Struct
    
    Calc-->>Analyzer: SimilarityResult
    deactivate Calc
```

**Key Points**:
- Normalization preserves method names (critical for semantic correctness)
- Variation tracking aligns tokens using LCS indices
- Three algorithms run in parallel for efficiency
- Weighted combination balances different similarity aspects

---

## 4. Variation Tracking Flow

This diagram shows how differences between similar code blocks are identified and categorized.

```mermaid
sequenceDiagram
    participant Normalizer as TokenNormalizer
    participant VarTrack as VariationTracker
    participant TypeAnalyzer as TypeAnalyzer
    participant Scope as ScopeAnalyzer

    Normalizer->>VarTrack: trackVariations(tokens1, tokens2)
    activate VarTrack
    
    VarTrack->>VarTrack: computeLCSAlignment(tokens1, tokens2)
    Note right of VarTrack: Get alignment indices<br/>for matching
    
    loop For each aligned position
        VarTrack->>VarTrack: compareTokens(t1, t2)
        
        alt Tokens match
            VarTrack->>VarTrack: continue
        else Tokens differ
            VarTrack->>VarTrack: categorizeVariation(t1, t2)
            
            alt LITERAL variation
                VarTrack->>VarTrack: add to literalVariations
            else VARIABLE variation
                VarTrack->>VarTrack: add to variableVariations
            else METHOD_CALL variation
                VarTrack->>VarTrack: add to methodCallVariations
            else TYPE variation
                VarTrack->>VarTrack: add to typeVariations
            end
        end
    end
    
    VarTrack->>TypeAnalyzer: inferTypes(variations)
    activate TypeAnalyzer
    
    loop For each variation
        TypeAnalyzer->>Scope: getVariableType(varName)
        Scope-->>TypeAnalyzer: type
        TypeAnalyzer->>TypeAnalyzer: checkTypeCompatibility()
    end
    
    TypeAnalyzer-->>VarTrack: TypeCompatibility
    deactivate TypeAnalyzer
    
    VarTrack->>VarTrack: analyzeFeasibility(variations, types)
    
    alt All variations type-safe & <= 5 params
        VarTrack->>VarTrack: canParameterize = true
    else Too many variations or type conflicts
        VarTrack->>VarTrack: canParameterize = false
        VarTrack->>VarTrack: requiresManualReview = true
    end
    
    VarTrack-->>Normalizer: VariationAnalysis
    deactivate VarTrack
```

**Key Points**:
- Uses LCS alignment to correctly map variations
- Categorizes variations by type (literal, variable, method call, type)
- Type inference validates parameter extraction safety
- Limits to 5 parameters for maintainability

---

## 5. Refactoring Recommendation Flow

This diagram shows how refactoring suggestions are generated based on detected duplicates.

```mermaid
sequenceDiagram
    participant Clusterer as DuplicateClusterer
    participant Analyzer as RefactoringFeasibilityAnalyzer
    participant ParamExt as ParameterExtractor
    participant Scope as ScopeAnalyzer
    participant Strategy as StrategySelector

    Clusterer->>Analyzer: analyzeFeasibility(cluster)
    activate Analyzer
    
    Analyzer->>Scope: analyzeScopeAt(seq1)
    Scope-->>Analyzer: availableVariables, fields
    
    Analyzer->>Analyzer: checkScopeCompatibility(seq1, seq2)
    
    alt Lambda/Anonymous class detected
        Analyzer->>Analyzer: mark MANUAL_REVIEW_REQUIRED
    end
    
    Analyzer->>Analyzer: validateTypeCompatibility()
    
    alt Type conflicts found
        Analyzer-->>Clusterer: feasibility(requiresManualReview=true)
    else All checks pass
        Analyzer->>Strategy: selectStrategy(cluster)
        activate Strategy
        
        Strategy->>Strategy: isInTestClass?()
        
        alt In test class
            Strategy->>Strategy: isSetupCodeAtStart?()
            
            alt Setup at start & can parameterize
                Strategy-->>Analyzer: EXTRACT_TO_PARAMETERIZED_TEST
            else Setup at start & common code
                Strategy-->>Analyzer: EXTRACT_TO_BEFORE_EACH
            else General test code
                Strategy-->>Analyzer: EXTRACT_HELPER_METHOD
            end
        else In source class
            Strategy->>Strategy: isCrossClass?()
            
            alt Across multiple classes
                Strategy-->>Analyzer: EXTRACT_TO_UTILITY_CLASS
            else Within same class
                Strategy-->>Analyzer: EXTRACT_HELPER_METHOD
            end
        end
        deactivate Strategy
        
        Analyzer->>ParamExt: extractParameters(variations)
        activate ParamExt
        
        ParamExt->>ParamExt: groupByType(variations)
        ParamExt->>ParamExt: inferParameterNames()
        ParamExt->>ParamExt: orderParameters()
        Note right of ParamExt: Required before optional<br/>Primitives before objects<br/>Alphabetical within groups
        
        ParamExt-->>Analyzer: List<ParameterSpec>
        deactivate ParamExt
        
        Analyzer->>Analyzer: suggestMethodName()
        Analyzer->>Analyzer: calculateConfidence()
        Analyzer->>Analyzer: estimateLOCReduction()
        
        Analyzer-->>Clusterer: RefactoringRecommendation
    end
    deactivate Analyzer
    
    Clusterer->>Clusterer: attachRecommendation(cluster)
```

**Key Points**:
- Strategy selection based on code location (test vs source)
- Test-specific strategies (@BeforeEach, @ParameterizedTest)
- Parameter ordering follows best practices
- Confidence score reflects automation safety

---

## 6. Pre-Filtering Flow

This diagram shows how the three-stage filtering reduces comparison overhead.

```mermaid
sequenceDiagram
    participant Analyzer as DuplicationAnalyzer
    participant PreFilter as PreFilterChain
    participant SizeFilter as SizeFilter
    participant StructFilter as StructuralFilter
    participant LSH as LSHBucketingFilter

    Analyzer->>PreFilter: filterCandidates(sequences)
    activate PreFilter
    
    Note right of PreFilter: Input: N sequences<br/>Naive: N²/2 comparisons
    
    PreFilter->>SizeFilter: apply(pairs)
    activate SizeFilter
    
    loop For each sequence pair
        SizeFilter->>SizeFilter: |len(s1) - len(s2)| / max(len)
        
        alt Size difference > 30%
            SizeFilter->>SizeFilter: skip pair
            Note right of SizeFilter: ~95% reduction
        else Size compatible
            SizeFilter->>SizeFilter: keep pair
        end
    end
    
    SizeFilter-->>PreFilter: size-filtered candidates
    deactivate SizeFilter
    
    PreFilter->>StructFilter: apply(candidates)
    activate StructFilter
    
    loop For each candidate pair
        StructFilter->>StructFilter: extractSignature(s1)
        Note right of StructFilter: [IF, FOR, TRY]<br/>[@Test, @Transactional]
        
        StructFilter->>StructFilter: extractSignature(s2)
        StructFilter->>StructFilter: jaccardSimilarity(sig1, sig2)
        
        alt Jaccard < 0.5
            StructFilter->>StructFilter: skip pair
            Note right of StructFilter: ~50% reduction<br/>of remaining pairs
        else Structurally similar
            StructFilter->>StructFilter: keep pair
        end
    end
    
    StructFilter-->>PreFilter: structurally-filtered candidates
    deactivate StructFilter
    
    opt Cross-file analysis (if enabled)
        PreFilter->>LSH: apply(candidates)
        activate LSH
        
        loop For each sequence
            LSH->>LSH: generateWShingles(tokens, w=5)
            LSH->>LSH: computeMinHash(shingles, 128 hashes)
            LSH->>LSH: assignLSHBuckets(signature)
            Note right of LSH: band_size=4, rows=32
        end
        
        LSH->>LSH: groupByBucket()
        
        loop For each bucket
            LSH->>LSH: comparePairsInBucket()
            Note right of LSH: Only compare sequences<br/>in same bucket
        end
        
        LSH-->>PreFilter: LSH-filtered candidates
        Note right of LSH: ~99% reduction<br/>vs naive approach
        deactivate LSH
    end
    
    PreFilter-->>Analyzer: final candidates
    deactivate PreFilter
    
    Note over Analyzer: Performance Impact:<br/>2K sequences: 4M → 20K comparisons<br/>10K sequences: 100M → 200K comparisons<br/>50K sequences: 2.5B → 2.5M comparisons
```

**Key Points**:
- Three-stage filtering is critical for scalability
- Stage 1 (size) is cheapest, eliminates most incompatible pairs
- Stage 2 (structural) uses fast O(n) signatures
- Stage 3 (LSH) optional for cross-file analysis
- Total reduction: 99%+ for large projects

---

## Summary

These sequence diagrams illustrate:

1. **Single File Analysis** - Core detection loop for one file
2. **Project-Wide Analysis** - Scaling to entire codebases
3. **Similarity Calculation** - Hybrid algorithm combining LCS, Levenshtein, and structural analysis
4. **Variation Tracking** - Precise difference identification for parameter extraction
5. **Refactoring Recommendation** - Intelligent strategy selection based on context
6. **Pre-Filtering** - Performance optimization reducing O(N²) overhead

All flows leverage existing Antikythera infrastructure (`AbstractCompiler`, `Settings`) and JavaParser's AST, avoiding unnecessary reinvention while focusing on the unique value: semantic duplicate detection with automated refactoring capabilities.
