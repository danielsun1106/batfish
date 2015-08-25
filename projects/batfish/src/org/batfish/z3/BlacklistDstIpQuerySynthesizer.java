package org.batfish.z3;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.batfish.collections.NodeInterfacePair;
import org.batfish.representation.Configuration;
import org.batfish.representation.Interface;
import org.batfish.representation.Ip;
import org.batfish.representation.Prefix;
import org.batfish.z3.node.AndExpr;
import org.batfish.z3.node.BooleanExpr;
import org.batfish.z3.node.EqExpr;
import org.batfish.z3.node.LitIntExpr;
import org.batfish.z3.node.NotExpr;
import org.batfish.z3.node.QueryExpr;
import org.batfish.z3.node.QueryRelationExpr;
import org.batfish.z3.node.RuleExpr;
import org.batfish.z3.node.SaneExpr;
import org.batfish.z3.node.VarIntExpr;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Z3Exception;

public class BlacklistDstIpQuerySynthesizer implements QuerySynthesizer {

   private Set<Ip> _blacklistIps;

   public BlacklistDstIpQuerySynthesizer(Set<Ip> explicitBlacklistIps,
         Set<String> blacklistNodes,
         Set<NodeInterfacePair> blacklistInterfaces,
         Map<String, Configuration> configurations) {
      _blacklistIps = new TreeSet<Ip>();
      if (explicitBlacklistIps != null) {
         _blacklistIps.addAll(explicitBlacklistIps);
      }
      if (blacklistNodes != null) {
         for (String hostname : blacklistNodes) {
            Configuration node = configurations.get(hostname);
            for (Interface iface : node.getInterfaces().values()) {
               if (iface.getActive()) {
                  Prefix prefix = iface.getPrefix();
                  if (prefix != null) {
                     _blacklistIps.add(prefix.getAddress());
                  }
               }
            }
         }
      }
      if (blacklistInterfaces != null) {
         for (NodeInterfacePair p : blacklistInterfaces) {
            String hostname = p.getHostname();
            String ifaceName = p.getInterface();
            Configuration node = configurations.get(hostname);
            Interface iface = node.getInterfaces().get(ifaceName);
            if (iface.getActive()) {
               Prefix prefix = iface.getPrefix();
               if (prefix != null) {
                  _blacklistIps.add(prefix.getAddress());
               }
            }
         }
      }
   }

   @Override
   public NodProgram getNodProgram(NodProgram baseProgram) throws Z3Exception {
      NodProgram program = new NodProgram(baseProgram.getContext());
      AndExpr queryConditions = new AndExpr();
      queryConditions.addConjunct(SaneExpr.INSTANCE);
      for (Ip blacklistIp : _blacklistIps) {
         BooleanExpr blacklistIpCondition = new NotExpr(new EqExpr(
               new VarIntExpr(Synthesizer.DST_IP_VAR), new LitIntExpr(
                     blacklistIp)));
         queryConditions.addConjunct(blacklistIpCondition);
      }
      RuleExpr queryRule = new RuleExpr(queryConditions,
            QueryRelationExpr.INSTANCE);
      List<BoolExpr> rules = program.getRules();
      rules.add(queryRule.toBoolExpr(baseProgram));
      QueryExpr query = new QueryExpr(QueryRelationExpr.INSTANCE);
      BoolExpr queryBoolExpr = query.toBoolExpr(baseProgram);
      program.getQueries().add(queryBoolExpr);
      return program;
   }

   @Override
   public String getQueryText() {
      throw new UnsupportedOperationException(
            "no implementation for generated method"); // TODO Auto-generated
                                                       // method stub
   }

}
