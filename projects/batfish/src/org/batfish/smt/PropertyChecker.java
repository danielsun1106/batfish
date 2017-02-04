package org.batfish.smt;


import com.microsoft.z3.*;
import org.batfish.common.BatfishException;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.StaticRoute;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.smt.answers.SmtForwardingAnswerElement;
import org.batfish.smt.answers.SmtManyAnswerElement;
import org.batfish.smt.answers.SmtOneAnswerElement;
import org.batfish.smt.utils.PatternUtils;

import java.util.*;
import java.util.regex.Pattern;

public class PropertyChecker {

    public static AnswerElement computeForwarding(IBatfish batfish, String destination) {
        Prefix prefix = new Prefix(destination);
        Encoder encoder = new Encoder(batfish, Collections.singletonList(prefix));
        encoder.computeEncoding();

        if (encoder.getLogicalGraph().getEnvironmentVars().size() > 0) {
            System.out.println("Warning: forwarding computed for only a single concrete " +
                    "environment");
        }

        VerificationResult result = encoder.verify();

        Set<GraphEdge> edges = new HashSet<>();
        if (!result.getVerified()) {
            encoder.getSymbolicDecisions().getDataForwarding().forEach((router, edge, e) -> {
                String expr = e.toString();
                if (expr.contains("data-")) {
                    String valuation = result.getModel().get(expr);
                    if (valuation.equals("true")) {
                        edges.add(edge);
                    }
                }
            });
        }

        SmtForwardingAnswerElement answer = new SmtForwardingAnswerElement();
        answer.setResult(result);
        answer.setEdges(edges);

        return answer;
    }


    public static AnswerElement computeReachability(IBatfish batfish, Pattern n1, Pattern iface,
            Pattern n2) {
        Graph graph = new Graph(batfish);
        List<GraphEdge> destinationPorts = PatternUtils.findMatchingEdge(graph, n1, iface);
        List<String> sourceRouters = PatternUtils.findMatchingNodes(graph, n2);
        Map<String, VerificationResult> result = new HashMap<>();

        for (GraphEdge ge : destinationPorts) {
            Prefix destination = ge.getStart().getPrefix();
            Encoder enc = new Encoder(Collections.singletonList(destination), graph);
            enc.computeEncoding();

            PropertyAdder pa = new PropertyAdder(enc);
            Map<String, BoolExpr> reachableVars = pa.instrumentReachability(ge);

            Context ctx = enc.getCtx();
            Solver solver = enc.getSolver();

            BoolExpr allReach = ctx.mkBool(false);
            for (String router : sourceRouters) {
                BoolExpr reach = reachableVars.get(router);
                allReach = ctx.mkOr(allReach, ctx.mkNot(reach));
            }
            solver.add(allReach);

            VerificationResult res = enc.verify();
            result.put(ge.toString(), res);
        }

        SmtManyAnswerElement answer = new SmtManyAnswerElement();
        answer.setResult(result);
        return answer;
    }


    public static AnswerElement computeBlackHole(IBatfish batfish) {
        Graph graph = new Graph(batfish);
        Prefix p = new Prefix("0.0.0.0/0");
        Encoder enc = new Encoder(Collections.singletonList(p), graph);
        enc.computeEncoding();

        Context ctx = enc.getCtx();
        Solver solver = enc.getSolver();

        // Collect routers that have no host/environment edge
        List<String> toCheck = new ArrayList<>();
        graph.getEdgeMap().forEach((router, edges) -> {
            boolean check = true;
            for (GraphEdge edge : edges) {
                if (edge.getEnd() == null) {
                    check = false;
                    break;
                }
            }
            if (check) {
                toCheck.add(router);
            }
        });

        // Ensure the router never receives traffic and then drops the traffic
        BoolExpr someBlackHole = ctx.mkBool(false);

        for (String router : toCheck) {
            Map<GraphEdge, BoolExpr> edges = enc.getSymbolicDecisions().getDataForwarding().get
                    (router);
            BoolExpr doesNotFwd = ctx.mkBool(true);
            for (Map.Entry<GraphEdge, BoolExpr> entry : edges.entrySet()) {
                BoolExpr dataFwd = entry.getValue();
                doesNotFwd = ctx.mkAnd(doesNotFwd, ctx.mkNot(dataFwd));
            }

            BoolExpr isFwdTo = ctx.mkBool(false);
            Set<String> neighbors = graph.getNeighbors().get(router);
            for (String n : neighbors) {
                for (Map.Entry<GraphEdge, BoolExpr> entry : enc.getSymbolicDecisions()
                                                               .getDataForwarding().get(n)
                                                               .entrySet()) {
                    GraphEdge ge = entry.getKey();
                    BoolExpr fwd = entry.getValue();
                    if (router.equals(ge.getPeer())) {
                        isFwdTo = ctx.mkOr(isFwdTo, fwd);
                    }
                }
            }

            someBlackHole = ctx.mkOr(someBlackHole, ctx.mkAnd(isFwdTo, doesNotFwd));
        }

        solver.add(someBlackHole);

        VerificationResult result = enc.verify();

        SmtOneAnswerElement answer = new SmtOneAnswerElement();
        answer.setResult(result);
        return answer;
    }


    public static AnswerElement computeBoundedLength(IBatfish batfish, Pattern n1, Pattern iface,
            Pattern n2, int k) {
        Graph graph = new Graph(batfish);
        List<GraphEdge> destinationPorts = PatternUtils.findMatchingEdge(graph, n1, iface);
        List<String> sourceRouters = PatternUtils.findMatchingNodes(graph, n2);
        Map<String, VerificationResult> result = new HashMap<>();

        for (GraphEdge ge : destinationPorts) {
            Prefix destination = ge.getStart().getPrefix();
            Encoder enc = new Encoder(Collections.singletonList(destination), graph);
            enc.computeEncoding();

            PropertyAdder pa = new PropertyAdder(enc);
            Map<String, ArithExpr> lenVars = pa.instrumentPathLength(ge);

            Context ctx = enc.getCtx();
            Solver solver = enc.getSolver();

            // All routers bounded by a particular length
            BoolExpr allBounded = ctx.mkBool(false);
            for (String router : sourceRouters) {
                ArithExpr len = lenVars.get(router);
                ArithExpr bound = ctx.mkInt(k);
                allBounded = ctx.mkOr(allBounded, ctx.mkGt(len, bound));
            }
            solver.add(allBounded);

            VerificationResult res = enc.verify();
            result.put(ge.toString(), res);
        }

        SmtManyAnswerElement answer = new SmtManyAnswerElement();
        answer.setResult(result);
        return answer;
    }


    public static AnswerElement computeEqualLength(IBatfish batfish, Pattern n1, Pattern iface,
            Pattern n2) {
        Graph graph = new Graph(batfish);
        List<GraphEdge> destinationPorts = PatternUtils.findMatchingEdge(graph, n1, iface);
        List<String> sourceRouters = PatternUtils.findMatchingNodes(graph, n2);
        Map<String, VerificationResult> result = new HashMap<>();

        for (GraphEdge ge : destinationPorts) {
            Prefix destination = ge.getStart().getPrefix();
            Encoder enc = new Encoder(Collections.singletonList(destination), graph);
            enc.computeEncoding();

            PropertyAdder pa = new PropertyAdder(enc);
            Map<String, ArithExpr> lenVars = pa.instrumentPathLength(ge);

            Context ctx = enc.getCtx();
            Solver solver = enc.getSolver();

            // All routers have the same length through transitivity
            List<Expr> lens = new ArrayList<>();
            for (String router : sourceRouters) {
                lens.add(lenVars.get(router));
            }
            BoolExpr allEqual = PropertyAdder.allEqual(ctx, lens);

            solver.add(ctx.mkNot(allEqual));

            VerificationResult res = enc.verify();
            result.put(ge.toString(), res);
        }

        SmtManyAnswerElement answer = new SmtManyAnswerElement();
        answer.setResult(result);
        return answer;
    }


    public static AnswerElement computeLoadBalance(IBatfish batfish, Pattern n1, Pattern iface,
            Pattern n2, Pattern peers, int k) {
        Graph graph = new Graph(batfish);
        List<GraphEdge> destinationPorts = PatternUtils.findMatchingEdge(graph, n1, iface);
        List<String> sourceRouters = PatternUtils.findMatchingNodes(graph, n2);
        Map<String, List<String>> peerRouters = new HashMap<>();
        Map<String, VerificationResult> result = new HashMap<>();

        List<String> pRouters = PatternUtils.findMatchingNodes(graph, peers);

        // TODO: refactor this out separately
        for (String router : sourceRouters) {
            List<String> list = new ArrayList<>();
            peerRouters.put(router, list);
            Set<String> neighbors = graph.getNeighbors().get(router);
            for (String peer : pRouters) {
                if (neighbors.contains(peer)) {
                    list.add(peer);
                }
            }
        }

        for (GraphEdge ge : destinationPorts) {
            Prefix destination = ge.getStart().getPrefix();
            Encoder enc = new Encoder(Collections.singletonList(destination), graph);
            enc.computeEncoding();

            PropertyAdder pa = new PropertyAdder(enc);
            Map<String, ArithExpr> loadVars = pa.instrumentLoad(ge);

            Context ctx = enc.getCtx();
            Solver solver = enc.getSolver();

            // TODO: add threshold
            // All routers bounded by a particular length
            List<Expr> peerLoads = new ArrayList<>();
            peerRouters.forEach((router, allPeers) -> {
                // ArithExpr load = loadVars.get(router);
                for (String peer : allPeers) {
                    peerLoads.add(loadVars.get(peer));
                }
            });
            BoolExpr evenLoads = PropertyAdder.allEqual(ctx, peerLoads);
            solver.add(ctx.mkNot(evenLoads));

            VerificationResult res = enc.verify();
            result.put(ge.toString(), res);
        }

        SmtManyAnswerElement answer = new SmtManyAnswerElement();
        answer.setResult(result);
        return answer;
    }

    public static AnswerElement computeLocalConsistency(IBatfish batfish, Pattern n) {
        Graph graph = new Graph(batfish);
        List<String> routers = PatternUtils.findMatchingNodes(graph, n);

        System.out.println("Looking at routers...");
        for (String router : routers) {
            System.out.println("Found Router: " + router);
        }

        Map<String, VerificationResult> result = new HashMap<>();

        Prefix destination = new Prefix("0.0.0.0/0");

        int len = routers.size();
        if (len <= 1) {
            SmtManyAnswerElement answer = new SmtManyAnswerElement();
            answer.setResult(new TreeMap<>());
            return answer;
        }

        for (int i = 0; i < len - 1; i++) {
            String r1 = routers.get(i);
            String r2 = routers.get(i + 1);

            // TODO: reorder to encode after checking if we can compare them

            // Create transfer function for router 1
            Set<String> toModel1 = new TreeSet<>();
            toModel1.add(r1);
            Graph g1 = new Graph(batfish, toModel1);
            Encoder e1 = new Encoder(Collections.singletonList(destination), g1);
            e1.computeEncoding();

            Context ctx = e1.getCtx();
            Solver solver = e1.getSolver();

            // Create transfer function for router 2
            // Reuse the context and solver
            Set<String> toModel2 = new TreeSet<>();
            toModel2.add(r2);
            Graph g2 = new Graph(batfish, toModel2);
            Encoder e2 = new Encoder(e1, g2);
            e2.computeEncoding();

            // Ensure that the two routers have the same interfaces for comparison
            Pattern p = Pattern.compile(".*");
            List<GraphEdge> edges1 = PatternUtils.findMatchingEdge(g1, p, p);
            List<GraphEdge> edges2 = PatternUtils.findMatchingEdge(g2, p, p);
            Set<String> ifaces1 = interfaces(edges1);
            Set<String> ifaces2 = interfaces(edges2);

            if (!(ifaces1.containsAll(ifaces2) && ifaces2.containsAll(ifaces1))) {
                String msg = String.format("Routers %s and %s have different interfaces", r1, r2);
                System.out.println(msg);
                SmtManyAnswerElement answer = new SmtManyAnswerElement();
                answer.setResult(null);
                return answer;
            }

            // TODO: check running same protocols?

            Map<String, EnumMap<RoutingProtocol, Map<String, EnumMap<EdgeType,
                    LogicalGraphEdge>>>> lgeMap1 = logicalEdgeMap(e1);
            Map<String, EnumMap<RoutingProtocol, Map<String, EnumMap<EdgeType,
                    LogicalGraphEdge>>>> lgeMap2 = logicalEdgeMap(e2);

            BoolExpr equalEnvs = ctx.mkBool(true);
            BoolExpr equalOutputs = ctx.mkBool(true);
            BoolExpr equalIncomingAcls = ctx.mkBool(true);

            Configuration conf1 = g1.getConfigurations().get(r1);
            Configuration conf2 = g2.getConfigurations().get(r2);

            // Set environments equal
            for (RoutingProtocol proto1 : e1.getGraph().getProtocols().get(r1)) {
                for (ArrayList<LogicalGraphEdge> es : e1.getLogicalGraph().getLogicalGraphEdges()
                                                        .get(r1).get(proto1)) {
                    for (LogicalGraphEdge lge1 : es) {

                        String ifaceName = lge1.getEdge().getStart().getName();
                        LogicalGraphEdge lge2 = lgeMap2.get(r2).get(proto1).get(ifaceName).get
                                (lge1.getEdgeType());

                        if (lge1.getEdgeType() == EdgeType.IMPORT) {

                            SymbolicRecord vars1 = e1.getLogicalGraph().getEnvironmentVars().get
                                    (lge1);
                            SymbolicRecord vars2 = e2.getLogicalGraph().getEnvironmentVars().get
                                    (lge2);

                            BoolExpr acl1 = e1.getIncomingAcls().get(lge1.getEdge().getStart());
                            BoolExpr acl2 = e2.getIncomingAcls().get(lge2.getEdge().getStart());

                            if (acl1 == null) {
                                acl1 = ctx.mkBool(true);
                            }
                            if (acl2 == null) {
                                acl2 = ctx.mkBool(true);
                            }

                            equalIncomingAcls = ctx.mkAnd(equalIncomingAcls, ctx.mkEq(acl1, acl2));

                            boolean hasEnv1 = (vars1 != null);
                            boolean hasEnv2 = (vars2 != null);

                            if (hasEnv1 && hasEnv2) {
                                BoolExpr samePermitted = ctx.mkEq(vars1.getPermitted(),
                                        vars2.getPermitted());
                                equalEnvs = ctx.mkAnd(equalEnvs, samePermitted, e1.equal(conf1,
                                        proto1, vars1, vars2, lge1));
                            } else if (hasEnv1 || hasEnv2) {
                                System.out.println("Edge1: " + lge1);
                                System.out.println("Edge2: " + lge2);
                                throw new BatfishException("one had environment");
                            }

                        } else {

                            SymbolicRecord out1 = lge1.getSymbolicRecord();
                            SymbolicRecord out2 = lge2.getSymbolicRecord();

                            equalOutputs = ctx.mkAnd(equalOutputs, e1.equal(conf1, proto1, out1,
                                    out2, lge1));
                        }
                    }
                }
            }

            // TODO: check both have same environment variables (e.g., screw up configuring peer
            // connection)

            BoolExpr validDest;
            validDest = ignoredDestinations(ctx, e1, r1, conf1);
            validDest = ctx.mkAnd(validDest, ignoredDestinations(ctx, e2, r2, conf2));

            Map<String, GraphEdge> geMap2 = interfaceMap(edges2);
            BoolExpr sameForwarding = ctx.mkBool(true);
            for (GraphEdge ge1 : edges1) {
                GraphEdge ge2 = geMap2.get(ge1.getStart().getName());
                BoolExpr dataFwd1 = e1.getSymbolicDecisions().getDataForwarding().get(r1, ge1);
                BoolExpr dataFwd2 = e2.getSymbolicDecisions().getDataForwarding().get(r2, ge2);
                sameForwarding = ctx.mkAnd(sameForwarding, ctx.mkEq(dataFwd1, dataFwd2));
            }

            // Ensure packets are the same
            SymbolicPacket p1 = e1.getSymbolicPacket();
            SymbolicPacket p2 = e2.getSymbolicPacket();
            BoolExpr equalPackets = p1.mkEqual(p2);

            BoolExpr assumptions = ctx.mkAnd(equalEnvs, validDest, equalPackets);
            BoolExpr required = ctx.mkAnd(sameForwarding, equalOutputs, equalIncomingAcls);

            solver.add(assumptions);
            solver.add(ctx.mkNot(required));

            VerificationResult res = e2.verify();

            String name = r1 + "<-->" + r2;
            result.put(name, res);
        }

        SmtManyAnswerElement answer = new SmtManyAnswerElement();
        answer.setResult(result);
        return answer;
    }

    private static Set<String> interfaces(List<GraphEdge> edges) {
        Set<String> ifaces = new TreeSet<>();
        for (GraphEdge edge : edges) {
            ifaces.add(edge.getStart().getName());
        }
        return ifaces;
    }

    private static Map<String, EnumMap<RoutingProtocol, Map<String, EnumMap<EdgeType,
            LogicalGraphEdge>>>> logicalEdgeMap(Encoder enc) {
        Map<String, EnumMap<RoutingProtocol, Map<String, EnumMap<EdgeType, LogicalGraphEdge>>>>
                acc = new HashMap<>();
        enc.getLogicalGraph().getLogicalGraphEdges().forEach((router, map) -> {
            EnumMap<RoutingProtocol, Map<String, EnumMap<EdgeType, LogicalGraphEdge>>> mapAcc =
                    new EnumMap<>(RoutingProtocol.class);
            acc.put(router, mapAcc);
            map.forEach((proto, edges) -> {
                Map<String, EnumMap<EdgeType, LogicalGraphEdge>> edgesMap = new HashMap<>();
                mapAcc.put(proto, edgesMap);
                for (ArrayList<LogicalGraphEdge> xs : edges) {
                    for (LogicalGraphEdge lge : xs) {
                        // Should have import since only connected to environment
                        String ifaceName = lge.getEdge().getStart().getName();
                        EnumMap<EdgeType, LogicalGraphEdge> typeMap = edgesMap.get(ifaceName);
                        if (typeMap == null) {
                            EnumMap<EdgeType, LogicalGraphEdge> m = new EnumMap<>(EdgeType.class);
                            m.put(lge.getEdgeType(), lge);
                            edgesMap.put(ifaceName, m);
                        } else {
                            typeMap.put(lge.getEdgeType(), lge);
                        }

                    }
                }

            });
        });
        return acc;
    }

    private static BoolExpr ignoredDestinations(Context ctx, Encoder e1, String r1, Configuration
            conf1) {
        // Don't consider directly connected routes
        BoolExpr validDest = ctx.mkBool(true);
        for (RoutingProtocol proto1 : e1.getGraph().getProtocols().get(r1)) {
            if (proto1 == RoutingProtocol.CONNECTED) {
                List<Prefix> prefixes = e1.getOriginatedNetworks(conf1, proto1);
                BoolExpr dest = e1.relevantOrigination(prefixes);
                validDest = ctx.mkAnd(validDest, ctx.mkNot(dest));
            }
        }
        return validDest;
    }

    private static Map<String, GraphEdge> interfaceMap(List<GraphEdge> edges) {
        Map<String, GraphEdge> ifaceMap = new HashMap<>();
        for (GraphEdge edge : edges) {
            ifaceMap.put(edge.getStart().getName(), edge);
        }
        return ifaceMap;
    }

    public static AnswerElement computeMultipathConsistency(IBatfish batfish, Pattern n1, Pattern
            iface) {
        Graph graph = new Graph(batfish);
        List<GraphEdge> destinationPorts = PatternUtils.findMatchingEdge(graph, n1, iface);
        Map<String, VerificationResult> result = new HashMap<>();

        for (GraphEdge ge : destinationPorts) {
            Prefix destination = ge.getStart().getPrefix();
            Encoder enc = new Encoder(Collections.singletonList(destination), graph);
            enc.computeEncoding();

            PropertyAdder pa = new PropertyAdder(enc);
            Map<String, BoolExpr> reachableVars = pa.instrumentReachability(ge);

            Context ctx = enc.getCtx();
            Solver solver = enc.getSolver();

            // All neighbor forwarded to have the same length
            BoolExpr acc = ctx.mkBool(false);
            for (Map.Entry<String, Configuration> entry : graph.getConfigurations().entrySet()) {
                String router = entry.getKey();
                BoolExpr reach = reachableVars.get(router);
                BoolExpr bad = ctx.mkBool(false);
                for (GraphEdge edge : graph.getEdgeMap().get(router)) {
                    BoolExpr dataFwd = enc.getSymbolicDecisions().getDataForwarding().get(router,
                            edge);
                    BoolExpr ctrFwd = enc.getSymbolicDecisions().getControlForwarding().get
                            (router, edge);
                    bad = ctx.mkOr(bad, ctx.mkAnd(ctrFwd, ctx.mkNot(dataFwd)));
                }
                acc = ctx.mkOr(acc, ctx.mkAnd(reach, bad));
            }
            solver.add(acc);
            VerificationResult res = enc.verify();
            // result.debug();
            result.put(ge.toString(), res);
        }

        SmtManyAnswerElement answer = new SmtManyAnswerElement();
        answer.setResult(result);
        return answer;
    }

    public static AnswerElement computeRoutingLoop(IBatfish batfish) {
        Graph graph = new Graph(batfish);

        // Collect all relevant destinations
        List<Prefix> prefixes = new ArrayList<>();
        graph.getStaticRoutes().forEach((router, ifaceMap) -> {
            ifaceMap.forEach((ifaceName, srs) -> {
                for (StaticRoute sr : srs) {
                    prefixes.add(sr.getNetwork());
                }
            });
        });

        // Collect all routers that use static routes as a
        // potential place node along a loop
        List<String> routers = new ArrayList<>();
        graph.getConfigurations().forEach((router, conf) -> {
            if (conf.getDefaultVrf().getStaticRoutes().size() > 0) {
                routers.add(router);
            }
        });

        Encoder enc = new Encoder(prefixes, graph);
        enc.computeEncoding();
        Context ctx = enc.getCtx();
        Solver solver = enc.getSolver();

        PropertyAdder pa = new PropertyAdder(enc);

        BoolExpr someLoop = ctx.mkBool(false);
        for (String router : routers) {
            BoolExpr hasLoop = pa.instrumentLoop(router);
            someLoop = ctx.mkOr(someLoop, hasLoop);
        }
        solver.add(someLoop);

        VerificationResult result = enc.verify();

        SmtOneAnswerElement answer = new SmtOneAnswerElement();
        answer.setResult(result);
        return answer;
    }

}
