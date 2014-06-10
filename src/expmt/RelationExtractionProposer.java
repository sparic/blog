/*
 * Copyright (c) 2014, Regents of the University of California
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in
 *   the documentation and/or other materials provided with the
 *   distribution.  
 *
 * * Neither the name of the University of California, Berkeley nor
 *   the names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior 
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package expmt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import blog.bn.BayesNetVar;
import blog.bn.DerivedVar;
import blog.bn.NumberVar;
import blog.bn.RandFuncAppVar;
import blog.common.Util;
import blog.common.numerical.JamaMatrixLib;
import blog.distrib.Beta;
import blog.distrib.Dirichlet;
import blog.model.Evidence;
import blog.model.FuncAppTerm;
import blog.model.FunctionSignature;
import blog.model.Model;
import blog.model.NonGuaranteedObject;
import blog.model.NonRandomFunction;
import blog.model.OriginFunction;
import blog.model.POP;
import blog.model.RandomFunction;
import blog.model.Type;
import blog.sample.Proposer;
import blog.world.DefaultPartialWorld;
import blog.world.PartialWorld;
import blog.world.PartialWorldDiff;

/**
 * Proposal distribution for the relation extraction model.
 */
public class RelationExtractionProposer implements Proposer {

  // Instance variables
  private Model model;
  private Evidence evidence;
  private List queries; // of Query

  private Type relType; // Relation
  private Type entType; // Entity
  private Type factType; // Fact
  private Type trigType; // Trigger
  private Type sentType; // Sentence

  private POP factPOP;

  // parameters
  private Integer alpha;
  private Integer beta;
  private Double dir_alpha;

  // random variable functions as described by the model
  protected RandomFunction sparsityFunc;
  protected RandomFunction holdsFunc;
  protected RandomFunction thetaFunc;
  protected RandomFunction sourceFactFunc;
  protected RandomFunction subjectFunc;
  protected RandomFunction objectFunc;
  protected RandomFunction triggerIDFunc;
  protected RandomFunction verbFunc;

  // Origin functions
  protected OriginFunction relFunc;
  protected OriginFunction arg1Func;
  protected OriginFunction arg2Func;

  // HashMap for facts
  protected HashMap<String, Object> factMap;

  // Random Number Generate
  private Random rng;

  // HashMap of supporting fact -> List of sentences that express it
  protected HashMap<Object, List> supportedFacts;

  // HashMap for true facts -> List of sentences that express it
  protected HashMap<Object, Set> trueFacts;
  protected HashMap<String, HashMap<Object, List>> trueFactDiff;

  // HashSet of labeled sentences
  protected HashSet labeledSentences;

  // HashSet of relevant arg pairs; used for holdsSwitch: Convention: Arg1 Arg2
  protected HashMap<String, List> relevantArgPairs;

  // Debug
  private int count;

  // For parallelism
  private boolean feasible = false;

  /**
   * Creates a new RelationExtractionProposer object for the given model.
   * 
   * Set the model, types, and random functions
   */
  public RelationExtractionProposer(Model model, Properties properties) {

    // Set model
    this.model = model;

    // Set types
    relType = Type.getType("Relation");
    entType = Type.getType("Entity");
    factType = Type.getType("Fact");
    trigType = Type.getType("Trigger");
    sentType = Type.getType("Sentence");

    // Set Random Functions
    sparsityFunc = (RandomFunction) model.getFunction(new FunctionSignature(
        "Sparsity", relType));
    holdsFunc = (RandomFunction) model.getFunction(new FunctionSignature(
        "Holds", factType));
    thetaFunc = (RandomFunction) model.getFunction(new FunctionSignature(
        "Theta", relType));
    sourceFactFunc = (RandomFunction) model.getFunction(new FunctionSignature(
        "SourceFact", sentType));
    subjectFunc = (RandomFunction) model.getFunction(new FunctionSignature(
        "Subject", sentType));
    objectFunc = (RandomFunction) model.getFunction(new FunctionSignature(
        "Object", sentType));
    triggerIDFunc = (RandomFunction) model.getFunction(new FunctionSignature(
        "TriggerID", sentType));
    verbFunc = (RandomFunction) model.getFunction(new FunctionSignature("Verb",
        sentType));

    // Set Origin Functions
    relFunc = (OriginFunction) model.getFunction(new FunctionSignature("Rel",
        factType));
    arg1Func = (OriginFunction) model.getFunction(new FunctionSignature("Arg1",
        factType));
    arg2Func = (OriginFunction) model.getFunction(new FunctionSignature("Arg2",
        factType));

    // Set POPs
    factPOP = (POP) factType.getPOPs().iterator().next();

    // Set parameter constants
    alpha = (Integer) model.getConstantValue("alpha");
    beta = (Integer) model.getConstantValue("beta");
    dir_alpha = (Double) model.getConstantValue("dir_alpha");

    // Random Number Generator
    // rng = new Random();

  }

  /**
   * Initialization:
   * 1) Set all observed variables (Subject, Object, Verb, TriggerID)
   * 2) Set number variables for facts
   * 3) Set SourceFact for all labeled sentences
   * 4) Connected Components
   * a) Find connected Components
   * b) Set sourceFacts
   * 5) Sample everything else
   * b) Sparsity
   * c) Holds for RELEVANT FACTS
   * d) Theta
   * 
   */
  public PartialWorldDiff initialize(Evidence evidence, List queries) {

    // Set evidence and queries
    this.evidence = evidence;
    this.queries = queries;

    PartialWorldDiff world;

    // Sample until a world with nonzero probability world has been sampled.
    do {

      PartialWorld underlying = new DefaultPartialWorld(getTypes());
      world = new PartialWorldDiff(underlying);

      // 1) Set observed variables
      for (Iterator iter = evidence.getEvidenceVars().iterator(); iter
          .hasNext();) {
        BayesNetVar evidenceVar = (BayesNetVar) iter.next();
        if (!(evidenceVar instanceof RandFuncAppVar)) {
          continue;
        }
        RandFuncAppVar var = (RandFuncAppVar) evidenceVar;
        RandomFunction varFunc = var.func();

        if (varFunc == verbFunc) {
          Object sentence = var.args()[0]; // Get the sentence s
          int triggerIndex = trigType.getGuaranteedObjIndex(evidence
              .getObservedValue(var)); // Assumes triggers are Distinct
          RandFuncAppVar trigFuncVar = new RandFuncAppVar(triggerIDFunc,
              Collections.singletonList(sentence));
          world.setValue(trigFuncVar, triggerIndex);
        }

        world.setValue(var, evidence.getObservedValue(var));
      }

      // Create hashmap where key is a unique relation/arg pair, value is a fact
      factMap = new HashMap<String, Object>();
      trueFacts = new HashMap<Object, Set>();
      relevantArgPairs = new HashMap<String, List>();

      // 2) Set number variables of RELEVANT FACTS: DATADRIVEN MCMC
      for (Object sentence : sentType.getGuaranteedObjects()) {

        Object arg1 = world.getValue(makeVar(subjectFunc, sentence));
        Object arg2 = world.getValue(makeVar(objectFunc, sentence));

        // Add to relevant arg pair hashset
        relevantArgPairs.put(arg1.toString() + ' ' + arg2.toString(),
            Arrays.asList(arg1, arg2));

        for (Object rel : relType.getGuaranteedObjects()) {

          // Use a NumberVar to grab the fact
          Object[] factArgs = { rel, arg1, arg2 };
          NumberVar nFacts = new NumberVar(factPOP, factArgs);

          // Instantiate the number variable, by definition of the model
          world.setValue(nFacts, 1);

        }
      }

      // 3) This is for labeled sentences, where a relation of a sentence is
      // observed (along with the arg pair, of course)
      labeledSentences = new HashSet();
      for (Iterator iter = evidence.getEvidenceVars().iterator(); iter
          .hasNext();) {

        BayesNetVar evidenceVar = (BayesNetVar) iter.next();

        // This assumes the observation looks like this: obs
        // Rel(SourceFact(Sent[1])) = R[3];
        // This will make that evidence var a DerivedVar
        if (!(evidenceVar instanceof DerivedVar)) {
          continue;
        }

        // Absolute NO IDEA how this works.. I thought it was supposed to return
        // an ArgSpec
        FuncAppTerm evidenceVarArgs = (FuncAppTerm) ((DerivedVar) evidenceVar)
            .getArgSpec();
        FuncAppTerm argOfEvidenceVarArgs = (FuncAppTerm) evidenceVarArgs
            .getArgs()[0];
        FuncAppTerm funcOfSentence = (FuncAppTerm) argOfEvidenceVarArgs
            .getArgs()[0];
        NonRandomFunction nonRandomSentence = (NonRandomFunction) funcOfSentence
            .getFunction();
        Object sentence = nonRandomSentence.getInterpretation().getValue(
            Collections.EMPTY_LIST);

        // Add sentence to labeledSentences set
        labeledSentences.add(sentence);

        Object rel = evidence.getObservedValue(evidenceVar);
        Object arg1 = world.getValue(makeVar(subjectFunc, sentence));
        Object arg2 = world.getValue(makeVar(objectFunc, sentence));

        // Set the sourceFact value in the world
        Object fact = getFact(rel, arg1, arg2, world);
        world.setValue(makeVar(sourceFactFunc, sentence), fact);

        // Set the Holds value in the world
        world.setValue(makeVar(holdsFunc, fact), true);

        // Add the sentence to trueFacts hashmap
        addToTrueFacts(fact, sentence);
      }

      // 4) Connected Components stuff
      HashMap<Integer, HashSet> connectedComponents = new HashMap<Integer, HashSet>();
      HashSet<Object> explored = new HashSet<Object>();
      // There are no explicit edges in this graph. In order to test whether a
      // sentence belongs
      // to a specific component, we can hash a string of it's arg pair or it's
      // trigger to a
      // set of arg pairs and trigger that the component currently has.
      HashMap<Integer, HashSet> connectedComponentEdgeRep = new HashMap<Integer, HashSet>();

      // 4a) Run a DFS to find all connected components
      for (Object sentence : sentType.getGuaranteedObjects()) {
        if (explored.contains(sentence)) {
          continue;
        }

        // Create new component
        HashSet newComponent = new HashSet();
        newComponent.add(sentence);
        connectedComponents.put(connectedComponents.size() + 1, newComponent);

        // Add edge set representation
        HashSet newEdgeRepComponent = new HashSet();
        Object arg1 = world.getValue(makeVar(subjectFunc, sentence));
        Object arg2 = world.getValue(makeVar(objectFunc, sentence));
        Object trig = world.getValue(makeVar(verbFunc, sentence));
        newEdgeRepComponent.add(arg1.toString() + " | " + arg2.toString());
        newEdgeRepComponent.add(trig.toString());
        connectedComponentEdgeRep.put(connectedComponents.size() + 1,
            newEdgeRepComponent);

        // Add to explored set
        explored.add(sentence);

        // Iterate through every sentence, adding to the component. O(|S|^2),
        // where |S| = number of sentences
        boolean changed = true;
        while (changed) {
          changed = false;
          for (Object sent : sentType.getGuaranteedObjects()) {
            if (explored.contains(sent)) {
              continue;
            }
            // Grab arg pair and trigger
            Object sArg1 = world.getValue(makeVar(subjectFunc, sent));
            Object sArg2 = world.getValue(makeVar(objectFunc, sent));
            Object sTrig = world.getValue(makeVar(verbFunc, sent));
            if (newEdgeRepComponent.contains(sArg1.toString() + " | "
                + sArg2.toString())
                || newEdgeRepComponent.contains(sTrig.toString())) {
              newComponent.add(sent); // Add to component
              newEdgeRepComponent.add(sArg1.toString() + " | "
                  + sArg2.toString()); // Add arg pair string to edge
                                       // representation set
              newEdgeRepComponent.add(sTrig.toString()); // Add trigger to edge
                                                         // representation set
              explored.add(sent); // Add to explored set
              changed = true;
            }
          }
        }
      }

      // Assign relations and source facts to connected components.
      // Each connected component gets one relation, unless there
      // is more than 1 observation for a component. Then we select randomly
      // from them.

      // 4b) Iterate through components and set sourceFacts
      for (Set component : connectedComponents.values()) {

        // Search for observed sentence
        HashSet relations = new HashSet();
        for (Object sent : component) {
          if (labeledSentences.contains(sent)) {
            Object rel = ((NonGuaranteedObject) world.getValue(makeVar(
                sourceFactFunc, sent))).getOriginFuncValue(relFunc);
            relations.add(rel);
          }
        }

        // Figure out what to do depending on size of relations set
        if (relations.size() == 0) { // Add an unused relation
          Object componentRelation = relType.getGuaranteedObject(Util
              .randInt(relType.getGuaranteedObjects().size()));
          setSourceFactsForComponent(world, component, componentRelation);
        } else if (relations.size() == 1) {
          Object componentRelation = relations.iterator().next();
          setSourceFactsForComponent(world, component, componentRelation);
        } else { // More than 1 relation
          for (Object sent : component) {
            if (labeledSentences.contains(sent)) { // Don't touched a labeled
                                                   // sentence
              continue;
            }

            // Get arg pair info
            Object arg1 = world.getValue(makeVar(subjectFunc, sent));
            Object arg2 = world.getValue(makeVar(objectFunc, sent));

            // Sample the sentence
            Object rel = sampleUniformlyFromSet(relations);

            // Set the sourceFact value in the world
            Object fact = getFact(rel, arg1, arg2, world);
            world.setValue(makeVar(sourceFactFunc, sent), fact);

            // Set the Holds value in the world
            world.setValue(makeVar(holdsFunc, fact), true);

            // Add to trueFacts
            addToTrueFacts(fact, sent);
          }
        }
      }

      /*
       * // 3a) Bootstrap Observed Sentences by Trigger (also has to instantiate
       * some stuff)
       * HashMap<Object, ArrayList> triggerGroups = new HashMap<Object,
       * ArrayList>();
       * for (Object sentence : sentType.getGuaranteedObjects()) {
       * 
       * Object trigger = world.getValue(makeVar(verbFunc, sentence));
       * if (triggerGroups.containsKey(trigger)) {
       * triggerGroups.get(trigger).add(sentence);
       * } else {
       * ArrayList list = new ArrayList();
       * list.add(sentence);
       * triggerGroups.put(trigger, list);
       * }
       * }
       * 
       * 
       * // See if there is a label in a group, assign if so
       * for (ArrayList group : triggerGroups.values()) {
       * Object rel = null;
       * for (Object sent : group) {
       * if (world.isInstantiated(makeVar(sourceFactFunc, sent))) { // Only
       * labeled sentences have this
       * rel = ((NonGuaranteedObject) world.getValue(makeVar(sourceFactFunc,
       * sent))).getOriginFuncValue(relFunc);
       * }
       * }
       * 
       * // One of the sentences was observed, cluster everything in that
       * relation
       * if (rel != null) {
       * for (Object sent : group) {
       * if (world.isInstantiated(makeVar(sourceFactFunc, sent))) {
       * continue;
       * }
       * Object arg1 = world.getValue(makeVar(subjectFunc, sent));
       * Object arg2 = world.getValue(makeVar(objectFunc, sent));
       * Object sourceFact = getFact(rel, arg1, arg2, world);
       * world.setValue(makeVar(sourceFactFunc, sent), sourceFact);
       * world.setValue(makeVar(holdsFunc, sourceFact), true);
       * 
       * // Add the sentence to trueFacts hashmap
       * addToTrueFacts(sourceFact, sent);
       * 
       * //System.out.println("3a, Trig: Setting " + sent + " to " +
       * sourceFact);
       * }
       * }
       * }
       * 
       * 
       * 
       * // 3b) Bootstrap Observed Sentences by ArgPair
       * HashMap<Object, ArrayList> argPairGroups = new HashMap<Object,
       * ArrayList>();
       * for (Object sentence : sentType.getGuaranteedObjects()) {
       * Object arg1 = world.getValue(makeVar(subjectFunc, sentence));
       * Object arg2 = world.getValue(makeVar(objectFunc, sentence));
       * String argPair = arg1.toString() + arg2.toString();
       * 
       * if (argPairGroups.containsKey(argPair)) {
       * argPairGroups.get(argPair).add(sentence);
       * } else {
       * ArrayList list = new ArrayList();
       * list.add(sentence);
       * argPairGroups.put(argPair, list);
       * }
       * }
       * 
       * 
       * // See if there is a label in a group, assign if so
       * for (ArrayList group : argPairGroups.values()) {
       * Object rel = null;
       * for (Object sent : group) {
       * if (world.isInstantiated(makeVar(sourceFactFunc, sent))) {
       * rel = ((NonGuaranteedObject) world.getValue(makeVar(sourceFactFunc,
       * sent))).getOriginFuncValue(relFunc);
       * }
       * }
       * 
       * // One of the sentences was observed, cluster everything in that
       * relation
       * if (rel != null) {
       * for (Object sent : group) {
       * if (world.isInstantiated(makeVar(sourceFactFunc, sent))) {
       * continue;
       * }
       * Object arg1 = world.getValue(makeVar(subjectFunc, sent));
       * Object arg2 = world.getValue(makeVar(objectFunc, sent));
       * Object sourceFact = getFact(rel, arg1, arg2, world);
       * world.setValue(makeVar(sourceFactFunc, sent), sourceFact);
       * world.setValue(makeVar(holdsFunc, sourceFact), true);
       * 
       * // Add the sentence to trueFacts hashmap
       * addToTrueFacts(sourceFact, sent);
       * 
       * //System.out.println("3b, ArgPair: Setting " + sent + " to " +
       * sourceFact);
       * }
       * }
       * }
       * 
       * 
       * // 3c) For all other trigger groups, either bootstrap via triggers one
       * more time, or throw them into some random relation
       * // See if there is a label in a group, assign if so
       * for (ArrayList group : triggerGroups.values()) {
       * Object rel = null;
       * 
       * for (Object sent : group) {
       * if (world.isInstantiated(makeVar(sourceFactFunc, sent))) { // Only
       * labeled sentences have this
       * rel = ((NonGuaranteedObject) world.getValue(makeVar(sourceFactFunc,
       * sent))).getOriginFuncValue(relFunc);
       * }
       * }
       * 
       * 
       * // If one of the sentences was observed, cluster everything in that
       * relation
       * // Otherwise, choose a random relation
       * if (rel == null) {
       * rel =
       * relType.getGuaranteedObject(Util.randInt(relType.getGuaranteedObjects
       * ().size()));
       * }
       * 
       * for (Object sent : group) {
       * if (world.isInstantiated(makeVar(sourceFactFunc, sent))) {
       * continue;
       * }
       * Object arg1 = world.getValue(makeVar(subjectFunc, sent));
       * Object arg2 = world.getValue(makeVar(objectFunc, sent));
       * Object sourceFact = getFact(rel, arg1, arg2, world);
       * world.setValue(makeVar(sourceFactFunc, sent), sourceFact);
       * world.setValue(makeVar(holdsFunc, sourceFact), true);
       * 
       * // Add the sentence to trueFacts hashmap
       * addToTrueFacts(sourceFact, sent);
       * 
       * //System.out.println("3c, Trig: Setting " + sent + " to " +
       * sourceFact);
       * }
       * }
       */

      /*
       * // 3c) For all other observed sentences, choose SourceFact (basically
       * randomly choose a relation
       * // to go with the argument pair)
       * for (Object sentence : sentType.getGuaranteedObjects()) {
       * 
       * // Some of these may have been set from observing a relation or
       * bootstrapping
       * if (world.isInstantiated(makeVar(sourceFactFunc, sentence))) {
       * continue;
       * }
       * 
       * // Choose relation randomly from all relations
       * int relNum = Util.randInt(relType.getGuaranteedObjects().size());
       * Object rel = relType.getGuaranteedObject(relNum);
       * Object arg1 = world.getValue(makeVar(subjectFunc, sentence));
       * Object arg2 = world.getValue(makeVar(objectFunc, sentence));
       * 
       * // Set the sourceFact value in the world
       * Object fact = getFact(rel, arg1, arg2, world);
       * world.setValue(makeVar(sourceFactFunc, sentence), fact);
       * 
       * // Set the Holds value in the world
       * world.setValue(makeVar(holdsFunc, fact), true);
       * 
       * }
       */

      // 5a) Sample Beta(r) for each r
      Integer[] params = { alpha, beta };
      Beta sparsitySampler = new Beta(Arrays.asList(params));
      for (Object rel : relType.getGuaranteedObjects()) {
        RandFuncAppVar sparsity = makeVar(sparsityFunc, rel);
        world.setValue(sparsity,
            sparsitySampler.sampleVal(Collections.EMPTY_LIST, relType));
      }

      // 5b) Sample all holds(f) values OF RELEVANT FACTS if they haven't been
      // instantiated
      for (Object sentence : sentType.getGuaranteedObjects()) {

        Object arg1 = world.getValue(makeVar(subjectFunc, sentence));
        Object arg2 = world.getValue(makeVar(objectFunc, sentence));

        for (Object rel : relType.getGuaranteedObjects()) {

          // Get the fact, instantiate and sample if need be
          Object fact = getFact(rel, arg1, arg2, world);
          RandFuncAppVar holds = makeVar(holdsFunc, fact);
          Double sparsity = (Double) world.getValue(makeVar(sparsityFunc, rel));

          if (!world.isInstantiated(holds)) {
            if (Util.random() < sparsity) {
              world.setValue(holds, true);
              // Add the fact to the trueFacts
              addToTrueFacts(fact, null);
            } else {
              world.setValue(holds, false);
            }
          }
        }
      }

      // 5c) Sample Theta(r) for each relation r
      Dirichlet thetaSampler = new Dirichlet(trigType.getGuaranteedObjects()
          .size(), dir_alpha);
      for (Object rel : relType.getGuaranteedObjects()) {

        thetaSample(world, rel);

      }

      // Debug
      if (Util.verbose()) {
        System.out.println(world.toString());
      }

      // For parallelism. When using evidence.getEvidenceLogProb(world), must
      // synchronize
      feasible = this.evidence.getEvidenceLogProb(world) > -1E6;

    } while (!feasible);
    // End do-while loop

    System.out.println("Number of sentences: "
        + sentType.getGuaranteedObjects().size());
    System.out.println("Number of facts: " + relevantArgPairs.size()
        * relType.getGuaranteedObjects().size());

    count = 0;
    feasible = false; // Reset feasible variable

    return world;

  }

  private void setSourceFactsForComponent(PartialWorldDiff world,
      Set component, Object rel) {

    for (Object sentence : component) {
      if (labeledSentences.contains(sentence)) { // Don't touched a labeled
                                                 // sentence
        continue;
      }

      // Get arg pair info
      Object arg1 = world.getValue(makeVar(subjectFunc, sentence));
      Object arg2 = world.getValue(makeVar(objectFunc, sentence));

      // Set the sourceFact value in the world
      Object fact = getFact(rel, arg1, arg2, world);
      world.setValue(makeVar(sourceFactFunc, sentence), fact);

      // Set the Holds value in the world
      world.setValue(makeVar(holdsFunc, fact), true);

      // Add to trueFacts
      addToTrueFacts(fact, sentence);
    }

  }

  /**
   * Proposes a next state for the Markov chain given the current state. The
   * world argument is a PartialWorldDiff that the proposer can modify to create
   * the proposal; the saved version of this PartialWorldDiff is the state
   * before the proposal. Returns the log proposal ratio: log (q(x | x') / q(x'
   * | x))
   * 
   * The details of the proposal has been arranged nicely in a PDF file.
   * Please look to that file for details.
   */
  public double proposeNextState(PartialWorldDiff proposedWorld) {
    count++;
    // if (count % 10000 == 0) {
    // int t = 2;
    // }
    double logAcceptanceRatio;

    // Sample until a valid move has been made (where evidence was not changed)
    // do {
    double sample = Util.random();
    proposedWorld.revert();
    trueFactDiff = constructNewTrueFactDiff();
    // updateSupportedFacts(proposedWorld); // Used for sourceFactSwitch and
    // holdsSwitch
    if (sample < 0.4) {
      logAcceptanceRatio = blockSourceFactSwitch(proposedWorld);
    } else if (sample < 0.6) {
      logAcceptanceRatio = sourceFactSwitch(proposedWorld);
    } else if (sample < 0.7) {
      logAcceptanceRatio = holdsSwitch(proposedWorld);
    } else if (sample < 0.8) {
      logAcceptanceRatio = randomSparsitySample(proposedWorld);
    } else {
      logAcceptanceRatio = randomThetaSample(proposedWorld);
    }

    // For parallelism. When using evidence.getEvidenceLogProb(world), must
    // synchronize
    // synchronized (evidence) {
    // feasible = this.evidence.getEvidenceLogProb(proposedWorld) > -1E6;
    // if (!feasible) {
    // int i = 1 / 0;
    // }
    // }
    // } while (!feasible);

    return logAcceptanceRatio;
  }

  /**
   * Method for performing sourceFact(s) switch
   * 
   * 1) Choose a random sentence
   * 2) Create multinomial vector
   * 3) Sample relation from multinomial vector
   * 4) Set newSourceFact appropriately (update set of supported facts
   * accordingly)
   * 5) Sample previousSourceFact if unsupporting
   */
  private double sourceFactSwitch(PartialWorldDiff proposedWorld) {

    // Choose sentence s randomly from all sentences

    int sentNum = Util.randInt(sentType.getGuaranteedObjects().size());
    Object sentence = sentType.getGuaranteedObject(sentNum);
    // Don't touch a labeled sentence
    while (labeledSentences.contains(sentence)) {
      sentNum = Util.randInt(sentType.getGuaranteedObjects().size());
      sentence = sentType.getGuaranteedObject(sentNum);
    }

    return sourceFactSwitchForSentence(proposedWorld, sentence);
  }

  private double sourceFactSwitchForSentence(PartialWorldDiff proposedWorld,
      Object sentence) {
    // Get previous source fact
    Object previousSourceFact = proposedWorld.getValue(new RandFuncAppVar(
        sourceFactFunc, Collections.singletonList(sentence)));

    // Get previous source fact relation
    Object oldRel = ((NonGuaranteedObject) previousSourceFact)
        .getOriginFuncValue(relFunc);

    // Get triggerID(sentence)
    int triggerID = (Integer) proposedWorld.getValue(new RandFuncAppVar(
        triggerIDFunc, Collections.singletonList(sentence)));

    // Create multinomial vector M
    double[] M = new double[relType.getGuaranteedObjects().size()];
    double total = 0;
    for (int i = 0; i < relType.getGuaranteedObjects().size(); i++) {

      Object rel = relType.getGuaranteedObject(i);
      JamaMatrixLib theta = (JamaMatrixLib) proposedWorld
          .getValue(new RandFuncAppVar(thetaFunc, Collections
              .singletonList(rel)));
      M[i] = theta.elementAt(0, triggerID);
      total += M[i];

    }

    // Normalize M
    for (int i = 0; i < M.length; i++) {
      M[i] /= total;
    }

    // Sample relation from M
    int newRelNum = Util.sampleWithProbs(M);
    Object newRel = relType.getGuaranteedObject(newRelNum);

    // Pair this relation with arg pair to get new source fact
    Object arg1 = proposedWorld.getValue(new RandFuncAppVar(subjectFunc,
        Collections.singletonList(sentence)));
    Object arg2 = proposedWorld.getValue(new RandFuncAppVar(objectFunc,
        Collections.singletonList(sentence)));
    Object newSourceFact = getFact(newRel, arg1, arg2, proposedWorld);

    // Set new value of sourceFact in world, then set it to be true
    proposedWorld
        .setValue(
            new RandFuncAppVar(sourceFactFunc, Collections
                .singletonList(sentence)), newSourceFact);
    proposedWorld.setValue(
        makeVar(holdsFunc, Collections.singletonList(newSourceFact)), true);

    // Add to the diff
    if (!previousSourceFact.equals(newSourceFact)) {
      trueFactDiff.get("Add").put(newSourceFact,
          new ArrayList(Collections.singletonList(sentence)));
      trueFactDiff.get("Delete").put(previousSourceFact,
          new ArrayList(Collections.singletonList(sentence)));
    }

    // if (isSupported(previousSourceFact) != NEWisSupported("old",
    // previousSourceFact) || (isSupported(newSourceFact) !=
    // NEWisSupported("old", newSourceFact))) {
    // System.out.println("Here");
    // }

    // //Update supported facts hashmap accordingly
    // //Remove from previous source fact list
    // supportedFacts.get(previousSourceFact).remove(sentence);
    //
    //
    // // Remove previous source fact from supported facts hashmap if it is
    // expressed by 0 sentences
    // if (supportedFacts.get(previousSourceFact).size() == 0) {
    // supportedFacts.remove(previousSourceFact);
    // }
    //
    // // Add sentence to new source fact list
    // if (isSupported(newSourceFact)) {
    // supportedFacts.get(newSourceFact).add(sentence);
    // } else {
    // ArrayList sentences = new ArrayList();
    // sentences.add(sentence);
    // supportedFacts.put(newSourceFact, sentences);
    // }

    // Sample previous source fact if unsupporting
    if (!isSupported("new", previousSourceFact)) {
      Object psfRel = ((NonGuaranteedObject) previousSourceFact)
          .getOriginFuncValue(relFunc);
      double sparsity = (Double) proposedWorld.getValue(new RandFuncAppVar(
          sparsityFunc, Collections.singletonList(psfRel)));
      RandFuncAppVar holds = new RandFuncAppVar(holdsFunc,
          Collections.singletonList(previousSourceFact));
      if (Util.random() < sparsity) {
        proposedWorld.setValue(holds, true);
        // Add to diff
        trueFactDiff.get("Add").put(previousSourceFact,
            new ArrayList(Collections.singleton(null)));
      } else {
        proposedWorld.setValue(holds, false);
        // Add to diff
        trueFactDiff.get("Delete").put(previousSourceFact,
            new ArrayList(Collections.singleton(null)));
      }
    }

    // if (isSupported(previousSourceFact) != NEWisSupported("new",
    // previousSourceFact) || (isSupported(newSourceFact) !=
    // NEWisSupported("new", newSourceFact))) {
    // System.out.println("Here2");
    // }

    // Calculate and return log proposal ratio
    int oldRelNum = relType.getGuaranteedObjIndex(oldRel);

    // This is defined for going from state x (savedWorld) state y
    // (proposedWorld with the changes)
    // psf denotes Previous Source Fact
    double PSFySparsityValue = (Double) proposedWorld
        .getValue(new RandFuncAppVar(sparsityFunc, Collections
            .singletonList(oldRel)));
    boolean PSFyHoldsValue = (Boolean) proposedWorld
        .getValue(new RandFuncAppVar(holdsFunc, Collections
            .singletonList(previousSourceFact)));
    double logProbOfPSFy = Math.log((PSFyHoldsValue) ? PSFySparsityValue
        : (1 - PSFySparsityValue));

    // This is defined for going from state y to state x
    // Note that psf_x = nsf_y, and similarly psf_y = nsf_x
    PartialWorld oldWorld = proposedWorld.getSaved();
    double PSFxSparsityValue = (Double) oldWorld.getValue(new RandFuncAppVar(
        sparsityFunc, Collections.singletonList(newRel)));
    boolean PSFxHoldsValue = (Boolean) oldWorld.getValue(new RandFuncAppVar(
        holdsFunc, Collections.singletonList(newSourceFact)));
    double logProbOfPSFx = Math.log((PSFxHoldsValue) ? PSFxSparsityValue
        : (1 - PSFxSparsityValue));

    // PURELY FOR DEBUGGING PURPOSES
    // Calculate the log state ratio and print it
    // The two values, h(nsf_y) and h(nsf_x) are both true, since in their
    // respective moves
    // they were selected and forced to be true in order to express the
    // sentence. Therefore,
    // P(h(nsf_i)) is simply equal to the sparsity value of the corresponding
    // relation. Note
    // how this is different from P(h(psf_i)) in that we can assume that it
    // holds true.
    double logProbOfNSFy = Math.log((Double) proposedWorld
        .getValue(new RandFuncAppVar(sparsityFunc, Collections
            .singletonList(newRel))));
    double logProbOfNSFx = Math.log((Double) oldWorld
        .getValue(new RandFuncAppVar(sparsityFunc, Collections
            .singletonList(oldRel))));
    // int numTrueFactsInOldState = 0;
    // int numTrueFactsInNewState = 0;
    // for (Object fact : allFacts(proposedWorld)) {
    // if ((Boolean) oldWorld.getValue(new RandFuncAppVar(holdsFunc,
    // Collections.singletonList(fact)))) {
    // numTrueFactsInOldState++;
    // }
    // if ((Boolean) proposedWorld.getValue(new RandFuncAppVar(holdsFunc,
    // Collections.singletonList(fact)))) {
    // numTrueFactsInNewState++;
    // }
    // }

    double logTrueFactRatio = sentType.getGuaranteedObjects().size()
        * Math.log((float) numTrueFacts("old") / numTrueFacts("new"));
    double logStateRatio = logProbOfPSFy + logProbOfNSFy
        + Math.log(M[newRelNum]) - logProbOfPSFx - logProbOfNSFx
        - Math.log(M[oldRelNum]) + logTrueFactRatio;

    // now print it..
    // if (count % 100 == 0) {
    // System.out.println("Source Fact Iteration #: " + count);
    // System.out.println("Correct logStateRatio: " + logStateRatio);
    // System.out.println("Correct logTrueFactRatio: " + logTrueFactRatio);
    // int sdjfkl = 5+2; // lol this is here so I can put a breakpoint after the
    // system print
    // }

    boolean PSFxIsSupported = isSupported("old", newSourceFact);
    boolean PSFyIsSupported = isSupported("new", previousSourceFact);
    double logProposalRatio = Math.log(M[oldRelNum])
        + ((!PSFxIsSupported) ? logProbOfPSFx : 0) - Math.log(M[newRelNum])
        - ((!PSFyIsSupported) ? logProbOfPSFy : 0);

    double logAcceptanceRatio = logStateRatio + logProposalRatio;
    double acceptanceRatio = Math.exp(logAcceptanceRatio);

    return logAcceptanceRatio; // RelationExtractionMHSampler hack

    // return logProposalRatio;

  }

  /**
   * Method for performing a block sourceFact(s) switch
   * 
   * 1) Choose a random sentence s
   * 2) Find all other sentences with the same argument pair
   * Call this set movedSentences
   * 3) If they all exhibit the same fact,
   * 3a) Choose a relation r (sampling from M)
   * 3b) Set new fact to be sourceFact for all sentences in MovedSentences
   * 3c) Sample PSF
   * 4) Else, do sourceFactSwitch on s
   */
  private double blockSourceFactSwitch(PartialWorldDiff proposedWorld) {

    // Choose sentence s randomly from all sentences

    int sentNum = Util.randInt(sentType.getGuaranteedObjects().size());
    Object sentence = sentType.getGuaranteedObject(sentNum);
    // Don't touch a labeled sentence
    while (labeledSentences.contains(sentence)) {
      sentNum = Util.randInt(sentType.getGuaranteedObjects().size());
      sentence = sentType.getGuaranteedObject(sentNum);
    }

    // Get movedSentences set
    HashSet movedSentences = getAllOtherSentencesWithSameArgPair(sentence,
        proposedWorld);

    // Get previous source fact
    Object previousSourceFact = proposedWorld.getValue(makeVar(sourceFactFunc,
        sentence));

    // Check to see if all movedSentences has same previousSourceFact
    boolean samePreviousSourceFact = true;
    boolean oneSentenceIsLabeled = false;
    for (Object movedSentence : movedSentences) {
      Object psf = proposedWorld
          .getValue(makeVar(sourceFactFunc, movedSentence));
      if (!psf.equals(previousSourceFact)) {
        samePreviousSourceFact = false;
      }
      if (labeledSentences.contains(movedSentence)) {
        oneSentenceIsLabeled = true;
      }
    }

    // If not all points to same source fact or one sentence is labeled, just
    // normal source fact switch for
    // sentence s
    if (!samePreviousSourceFact || oneSentenceIsLabeled) {
      return sourceFactSwitchForSentence(proposedWorld, sentence);
    }

    /*
     * Block SourceFactSwitch Starts Here!
     */

    // Get previous source fact relation
    Object oldRel = ((NonGuaranteedObject) previousSourceFact)
        .getOriginFuncValue(relFunc);

    // Get triggerID's of all sentences
    int[] triggerIDs = new int[movedSentences.size()];
    int index = 0;
    for (Object sent : movedSentences) {
      triggerIDs[index] = (Integer) proposedWorld.getValue(makeVar(
          triggerIDFunc, sent));
      index++;
    }

    // Create the multinomial vector M
    double[] M = new double[relType.getGuaranteedObjects().size()];
    double total = 0;

    // The outer loop is for every relation
    for (int i = 0; i < relType.getGuaranteedObjects().size(); i++) {
      Object rel = relType.getGuaranteedObject(i);
      JamaMatrixLib theta = (JamaMatrixLib) proposedWorld.getValue(makeVar(
          thetaFunc, rel)); // \theta(r_i)
      M[i] = 1;
      // The inner loop is for every triggerID
      for (int s = 0; s < triggerIDs.length; s++) {
        M[i] *= theta.elementAt(0, triggerIDs[s]);
      }
      total += M[i];
    }

    // Normalize M
    for (int i = 0; i < M.length; i++) {
      M[i] /= total;
    }

    // Sample relation from M
    int newRelNum = Util.sampleWithProbs(M);
    Object newRel = relType.getGuaranteedObject(newRelNum);

    // Get new sourceFact, set it to be sourceFact for every sentence
    Object arg1 = proposedWorld.getValue(makeVar(subjectFunc, sentence));
    Object arg2 = proposedWorld.getValue(makeVar(objectFunc, sentence));
    Object newSourceFact = getFact(newRel, arg1, arg2, proposedWorld);
    proposedWorld.setValue(makeVar(holdsFunc, newSourceFact), true);

    // Set new sourceFact for every movedSentence
    // Add to diff
    trueFactDiff.get("Add").put(newSourceFact, new ArrayList());
    for (Object sent : movedSentences) {
      proposedWorld.setValue(makeVar(sourceFactFunc, sent), newSourceFact);
      trueFactDiff.get("Add").get(newSourceFact).add(sent);
    }

    // Sample previous source fact IF IT IS NOT THE SAME AS NEW SOURCE FACT
    if (!previousSourceFact.equals(newSourceFact)) {
      Object psfRel = ((NonGuaranteedObject) previousSourceFact)
          .getOriginFuncValue(relFunc);
      double sparsity = (Double) proposedWorld.getValue(makeVar(sparsityFunc,
          psfRel));
      RandFuncAppVar holds = makeVar(holdsFunc, previousSourceFact);
      if (Util.random() < sparsity) {
        proposedWorld.setValue(holds, true);
        // Add to diff
        trueFactDiff.get("Add").put(previousSourceFact,
            new ArrayList(Collections.singleton(null)));
      } else {
        proposedWorld.setValue(holds, false);
        // Add to diff
        trueFactDiff.get("Delete").put(previousSourceFact,
            new ArrayList(Collections.singleton(null)));
      }
    }

    // Calculate and return log acceptance ratio
    PartialWorld oldWorld = proposedWorld.getSaved();

    // The two values, h(nsf_y) and h(nsf_x) are both true, since in their
    // respective moves
    // they were selected and forced to be true in order to express the
    // sentence. Therefore,
    // P(h(nsf_i)) is simply equal to the sparsity value of the corresponding
    // relation.
    double logProbOfNSFy = Math.log((Double) proposedWorld.getValue(makeVar(
        sparsityFunc, newRel)));
    double logProbOfNSFx = Math.log((Double) oldWorld.getValue(makeVar(
        sparsityFunc, oldRel)));
    // int numTrueFactsInOldState = 0;
    // int numTrueFactsInNewState = 0;
    // for (Object fact : allFacts(proposedWorld)) {
    // if ((Boolean) oldWorld.getValue(new RandFuncAppVar(holdsFunc,
    // Collections.singletonList(fact)))) {
    // numTrueFactsInOldState++;
    // }
    // if ((Boolean) proposedWorld.getValue(new RandFuncAppVar(holdsFunc,
    // Collections.singletonList(fact)))) {
    // numTrueFactsInNewState++;
    // }
    // }

    double logTrueFactRatio = sentType.getGuaranteedObjects().size()
        * Math.log((float) numTrueFacts("old") / numTrueFacts("new"));

    // now print it..
    // if (count % 100 == 0) {
    // System.out.println("Source Fact Iteration #: " + count);
    // System.out.println("Correct logStateRatio: " + logStateRatio);
    // System.out.println("Correct logTrueFactRatio: " + logTrueFactRatio);
    // int sdjfkl = 5+2; // lol this is here so I can put a breakpoint after the
    // system print
    // }

    double logAcceptanceRatio = logProbOfNSFy - logProbOfNSFx
        + logTrueFactRatio;
    double acceptanceRatio = Math.exp(logAcceptanceRatio);

    return logAcceptanceRatio; // RelationExtractionMHSampler hack

    // return logProposalRatio;

  }

  // Linear scan through all other sentences
  private HashSet getAllOtherSentencesWithSameArgPair(Object sentence,
      PartialWorldDiff proposedWorld) {

    HashSet movedSentences = new HashSet();
    movedSentences.add(sentence);

    // Get arg pair
    Object arg1 = proposedWorld.getValue(makeVar(subjectFunc, sentence));
    Object arg2 = proposedWorld.getValue(makeVar(objectFunc, sentence));

    for (int i = 0; i < sentType.getGuaranteedObjects().size(); i++) {
      Object sent = sentType.getGuaranteedObject(i);
      Object sent_arg1 = proposedWorld.getValue(makeVar(subjectFunc, sent));
      Object sent_arg2 = proposedWorld.getValue(makeVar(objectFunc, sent));
      if (arg1.equals(sent_arg1) && arg2.equals(sent_arg2)) {
        movedSentences.add(sent);
      }
    }

    return movedSentences;
  }

  /**
   * Method for performing Holds(f) switch
   * 
   * 1) Calculate number of true facts
   * 2) Choose a random Holds(f) that is unsupported
   * 3) Sample it based on sparsity(Rel(f))
   * 4) Calculate number of true facts in new state and return log proposal
   * ratio
   */
  private double holdsSwitch(PartialWorldDiff proposedWorld) {

    // 1) Run through all facts, find the number of true facts in current state
    // and get set of unsupported facts
    // int numTrueFactsInOldState = 0;
    // Set unsupportedFacts = new HashSet();
    // for (Object fact : allFacts(proposedWorld)) {
    // if ((Boolean) proposedWorld.getValue(new RandFuncAppVar(holdsFunc,
    // Collections.singletonList(fact)))) {
    // numTrueFactsInOldState++;
    // }
    // if (!isSupported(fact)) {
    // unsupportedFacts.add(fact);
    // }
    // }

    // 2) Randomly choose a Holds(f) from unsupported set of facts
    Object factChoice;

    do {
      // Get a random relation
      int relNum = Util.randInt(relType.getGuaranteedObjects().size());
      Object rel = relType.getGuaranteedObject(relNum);
      // Get a random relevant arg pair
      List pairs = relevantArgPairs
          .get((String) sampleUniformlyFromSet(relevantArgPairs.keySet()));
      Object arg1 = pairs.get(0);
      Object arg2 = pairs.get(1);

      factChoice = getFact(rel, arg1, arg2, proposedWorld);

    } while (isSupported("old", factChoice));

    // 3) Sample it based on sparsity
    RandFuncAppVar holds = new RandFuncAppVar(holdsFunc,
        Collections.singletonList(factChoice));
    Object factChoiceRel = ((NonGuaranteedObject) factChoice)
        .getOriginFuncValue(relFunc);
    RandFuncAppVar sparsity = new RandFuncAppVar(sparsityFunc,
        Collections.singletonList(factChoiceRel));
    double sparsityValue = (Double) proposedWorld.getValue(sparsity);

    // Used for calculating number of true facts in new state
    boolean current = ((Boolean) proposedWorld.getValue(holds)).booleanValue();
    boolean sample;

    // Sampling process
    if (Util.random() < sparsityValue) {
      sample = true;
      // Add to diff
      trueFactDiff.get("Add").put(factChoice,
          new ArrayList(Collections.singleton(null)));
    } else {
      sample = false;
      trueFactDiff.get("Delete").put(factChoice,
          new ArrayList(Collections.singleton(null)));
    }
    proposedWorld.setValue(holds, sample); // Set the new value in the world

    // // 4) See if number of true facts changed
    // // Could also just run through all facts in proposed world, but this is
    // faster
    // int numTrueFactsInNewState = numTrueFactsInOldState;
    // if (!(current == sample)) {
    // if (current == true && sample == false) {
    // numTrueFactsInNewState--;
    // } else if (current == false && sample == true) {
    // numTrueFactsInNewState++;
    // }
    // }

    // Return log proposal ratio. More details in Tex file
    double logProbOfOldState = (current) ? Math.log(sparsityValue) : Math
        .log((1 - sparsityValue));
    double logProbOfNewState = (sample) ? Math.log(sparsityValue) : Math
        .log((1 - sparsityValue));

    // PURELY FOR DEBUGGING PURPOSES
    // Calculate the log state ratio and print it
    double logStateRatio = logProbOfNewState - logProbOfOldState
        + sentType.getGuaranteedObjects().size()
        * Math.log((float) numTrueFacts("old") / numTrueFacts("new"));
    // Print it here, maybe every 50 or 100 times, requires a counter instance
    // variable in this class
    // if (count % 100 == 0) {
    // System.out.println("holdsSwitch Iteration #: " + count);
    // System.out.println("Correct logStateRatio: " + logStateRatio);
    // int jklsf = 43;
    // }

    double logProposalRatio = logProbOfOldState - logProbOfNewState;

    double logAcceptanceRatio = logStateRatio + logProposalRatio;
    double acceptanceRatio = Math.exp(logAcceptanceRatio);
    return logAcceptanceRatio; // RelationExtractionMHSampler hack

    // return logProposalRatio;

  }

  // Uniformly sample an element from a Set s. Used in holdsSwitch() method
  private Object sampleUniformlyFromSet(Set s) {

    int n = Util.randInt(s.size());
    int index = 0;
    for (Object item : s) {
      if (index == n) {
        return item;
      }
      index++;
    }
    return null;

  }

  // private boolean isSupported(Object fact) {
  // return supportedFacts.containsKey(fact);
  // }

  // Private method for checking whether a fact is expressed by any sentences
  private boolean isSupported(String state, Object fact) {

    boolean s;

    if (state == "old") {
      // The trueFacts HashMap must have a list size of > 0
      if (trueFacts.containsKey(fact)) {
        s = trueFacts.get(fact).size() > 0;
      } else {
        s = false;
      }
    } else if (state == "new") {
      if (trueFacts.containsKey(fact)) {
        s = trueFacts.get(fact).size() > 0;
        // Check if it will be deleted or added
        if (trueFactDiff.get("Delete").containsKey(fact)) {
          // If we have fact : [null], means it will be deleted
          if (trueFactDiff.get("Delete").get(fact) == new ArrayList(
              Collections.singleton(null))) {
            s = false;
            // If all sentences get deleted, it is unsupported
          } else if (trueFacts.get(fact).size()
              - trueFactDiff.get("Delete").get(fact).size() == 0) {
            s = false;
          }
          // If we are adding sentences, it MUST be supported
        } else if (trueFactDiff.get("Add").containsKey(fact)
            && trueFactDiff.get("Add").get(fact) != new ArrayList(
                Collections.singleton(null))) {
          s = true;
        }
      } else { // Not originally true. Check trueFactDiff to see if it'll be
               // added
        s = false;
        if (trueFactDiff.get("Add").containsKey(fact)
            && trueFactDiff.get("Add").get(fact) != new ArrayList(
                Collections.singleton(null))) {
          s = true;
        }
      }
    } else { // Throw exception. I'm too lazy to actually have the method throw
             // a real exception..
      int e = 1 / 0;
      s = false;
    }
    return s;

  }

  /**
   * Method for performing sparsity sampling (this is Gibbs)
   * 
   * Hr := {Holds(f) : Rel(f) = r), refer for the Tex file for more details
   * 
   * Sample from posterior Beta distribution
   */
  private double randomSparsitySample(PartialWorldDiff proposedWorld) {

    // Choose relation randomly from all relations
    int relNum = Util.randInt(relType.getGuaranteedObjects().size());
    Object rel = relType.getGuaranteedObject(relNum);

    return sparsitySample(proposedWorld, rel);
  }

  private double sparsitySample(PartialWorldDiff proposedWorld, Object rel) {

    // The size of Hr is simply the number of unique combinations of different
    // entities
    int sizeOfHrf = (int) Math.pow(entType.getGuaranteedObjects().size(), 2);
    int numOfTrueFactsInHrf = 0;
    for (List pair : relevantArgPairs.values()) {
      Object fact = getFact(rel, pair.get(0), pair.get(1), proposedWorld);
      if (((Boolean) proposedWorld.getValue(new RandFuncAppVar(holdsFunc,
          Collections.singletonList(fact)))).booleanValue()) {
        numOfTrueFactsInHrf++;
      }
    }

    // Sample from posterior distribution, set the value in the world
    Integer[] params = { alpha + numOfTrueFactsInHrf,
        beta + sizeOfHrf - numOfTrueFactsInHrf };
    Beta sparsitySampler = new Beta(Arrays.asList(params));
    RandFuncAppVar sparsity = makeVar(sparsityFunc, rel);
    Object newVal = sparsitySampler.sampleVal(Collections.EMPTY_LIST, relType);
    proposedWorld.setValue(sparsity, newVal);

    // return Double.POSITIVE_INFINITY; // Gibbs, may have to figure this out
    // later with MHSampler.java
    return 0; // Gibbs, for RelationExtractionMHSampler hack

  }

  /**
   * Method for performing theta sampling (this is Gibbs)
   * 
   * Refer to the Tex file for more details
   * 
   * Sample from posterior Dirichlet Distribution
   */
  private double randomThetaSample(PartialWorldDiff proposedWorld) {

    // Choose relation randomly from all relations
    int relNum = Util.randInt(relType.getGuaranteedObjects().size());
    Object rel = relType.getGuaranteedObject(relNum);

    return thetaSample(proposedWorld, rel);
  }

  private double thetaSample(PartialWorldDiff proposedWorld, Object rel) {

    // Create parameter vector
    Double[] params = new Double[trigType.getGuaranteedObjects().size()];
    Arrays.fill(params, dir_alpha);

    // For each trigger (indexed by i), find how many TriggerID(s) rv's have
    // that value
    for (Object sentence : sentType.getGuaranteedObjects()) {
      RandFuncAppVar triggerID = new RandFuncAppVar(triggerIDFunc,
          Collections.singletonList(sentence));
      Object sourceFact = proposedWorld.getValue(makeVar(sourceFactFunc,
          Collections.singletonList(sentence)));
      Object sentRel = ((NonGuaranteedObject) sourceFact)
          .getOriginFuncValue(relFunc);
      if (sentRel == rel) {
        params[(Integer) proposedWorld.getValue(triggerID)]++;
      }
    }

    // Sample from posterior distribution, set the value in the world
    Dirichlet thetaSampler = new Dirichlet(Arrays.asList(params));
    RandFuncAppVar theta = new RandFuncAppVar(thetaFunc,
        Collections.singletonList(rel));
    Object newVal = thetaSampler.sampleVal(Collections.EMPTY_LIST, relType);
    proposedWorld.setValue(theta, newVal);

    // return Double.POSITIVE_INFINITY; // Gibbs, may have to figure this out
    // later with MHSampler.java
    return 0; // Gibbs, for RelationExtractionMHSampler hack

  }

  /**
   * These methods will not be used, but must be implemented to satisfy the
   * Proposer interface...
   */

  public void updateStats(boolean accepted) {

    if (accepted) {
      applyDiff();
    }

  }

  public void printStats() {
    // no stats to print
  }

  public PartialWorldDiff reduceToCore(PartialWorld curWorld, BayesNetVar var) {
    return null;
  }

  public double proposeNextState(PartialWorldDiff proposedWorld,
      BayesNetVar var, int i) {
    return 0;
  }

  public double proposeNextState(PartialWorldDiff proposedWorld, BayesNetVar var) {
    return 0;
  }

  // A method to create the key specific to the factMap (HashMap)
  private String generateKey(Object rel, Object arg1, Object arg2) {
    return "Rel: " + rel.toString() + ", Arg1: " + arg1.toString() + ", Arg2: "
        + arg2.toString();
  }

  private Object getFact(Object rel, Object arg1, Object arg2,
      PartialWorldDiff world) {

    // Use a NumberVar to grab the fact
    Object[] factArgs = { rel, arg1, arg2 };
    NumberVar nFacts = new NumberVar(factPOP, factArgs);
    Object value = world.getSatisfiers(nFacts).iterator().next(); // Should only
                                                                  // be one fact
                                                                  // that
                                                                  // satisfies
                                                                  // that number
                                                                  // statement

    return value;
  }

  private Collection allFacts(PartialWorldDiff world) {

    /*
     * List allRels = relType.getGuaranteedObjects();
     * List allEntities = entType.getGuaranteedObjects();
     * 
     * 
     * int numFacts = allRels.size() * allEntities.size() * allEntities.size();
     * Object[] allFacts = new Object[numFacts];
     * 
     * int index = 0;
     * for (Object rel : allRels) {
     * for (Object arg1 : allEntities) {
     * for (Object arg2 : allEntities) {
     * allFacts[index] = getFact(rel, arg1, arg2, world);
     * index++;
     * }
     * }
     * }
     */
    Collection allFacts = factMap.values();

    return allFacts;

  }

  // For debugging
  private ArrayList allTrueFacts(PartialWorldDiff world) {
    Collection facts = allFacts(world);

    ArrayList trueFacts = new ArrayList();

    for (Object fact : facts) {
      if ((Boolean) world.getValue(makeVar(holdsFunc,
          Collections.singletonList(fact)))) {
        trueFacts.add(fact);
      }
    }

    return trueFacts;

  }

  // Update the supportedFacts hashmap given the world
  // Need to do this just in case last sample wasn't accepted
  private void updateSupportedFacts(PartialWorldDiff world) {

    supportedFacts = new HashMap<Object, List>();

    for (Object sentence : sentType.getGuaranteedObjects()) {

      Object fact = world.getValue(makeVar(sourceFactFunc,
          Collections.singletonList(sentence)));

      // Update hashmap of supporting facts accordingly
      if (supportedFacts.containsKey(fact)) {
        supportedFacts.get(fact).add(sentence);
      } else {
        ArrayList sentences = new ArrayList();
        sentences.add(sentence);
        supportedFacts.put(fact, sentences);
      }

    }

  }

  private void addToTrueFacts(Object fact, Object sentence) {

    // If there is no sentence, just make sure it's in the hashmap
    if (sentence == null) {
      if (!trueFacts.containsKey(fact)) {
        Set sentences = new HashSet<Object>();
        trueFacts.put(fact, sentences);
      }
    } else { // If there is a sentence, update the hashmap accordingly
      if (trueFacts.containsKey(fact)) {
        trueFacts.get(fact).add(sentence);
      } else {
        Set sentences = new HashSet();
        sentences.add(sentence);
        trueFacts.put(fact, sentences);
      }
    }
  }

  private void deleteFromTrueFacts(Object fact, Object sentence) {
    // Assumes fact is present in trueFacts HashMap. Otherwise.. how can you
    // delete

    if (sentence == null) {
      trueFacts.remove(fact);
    } else {
      trueFacts.get(fact).remove(sentence);
    }
  }

  private int numTrueFacts(String state) {
    int num;

    // Trivial, just return the size of the trueFact HashMap
    if (state == "old") {
      num = trueFacts.size();

      // Trickier, find the number including what happens in the diff
    } else if (state == "new") {

      num = trueFacts.size();
      // If a fact in the diff isn't in the trueFacts HashMap, add 1
      for (Object fact : trueFactDiff.get("Add").keySet()) {
        if (!trueFacts.containsKey(fact)) {
          num++;
        }
      }
      // The fact MUST be deleted before we minus 1. By my convention, the value
      // must be [null]
      for (Object fact : trueFactDiff.get("Delete").keySet()) {
        if (trueFacts.containsKey(fact)
            && trueFactDiff.get("Delete").get(fact)
                .equals(new ArrayList(Collections.singleton(null)))) {
          num--;
        }
      }

    } else {
      // Throw an exception, but I'm too lazy to say that this throws exceptions
      num = 1 / 0;
    }
    return num;
  }

  protected void applyDiff() {

    // Delete first
    for (Object fact : trueFactDiff.get("Delete").keySet()) {
      List value = trueFactDiff.get("Delete").get(fact);
      for (Object sent : value) {
        deleteFromTrueFacts(fact, sent);
      }
    }

    // Add next
    for (Object fact : trueFactDiff.get("Add").keySet()) {
      List value = trueFactDiff.get("Add").get(fact);
      for (Object sent : value) {
        addToTrueFacts(fact, sent);
      }
    }
  }

  private HashMap constructNewTrueFactDiff() {

    HashMap TFD = new HashMap<String, HashMap<Object, List>>();
    TFD.put("Add", new HashMap<Object, List>());
    TFD.put("Delete", new HashMap<Object, List>());

    return TFD;

  }

  private RandFuncAppVar makeVar(RandomFunction func, List args) {
    return new RandFuncAppVar(func, args);
  }

  private RandFuncAppVar makeVar(RandomFunction func, Object obj) {
    return makeVar(func, Collections.singletonList(obj));
  }

  private Set getTypes() {
    Set idTypes = new HashSet();
    idTypes.add(relType);
    idTypes.add(entType);
    // idTypes.add(factType); // If commented, Fact objects result in
    // NonGuaranteedObjects
    idTypes.add(trigType);
    idTypes.add(sentType);
    return idTypes;
  }

}