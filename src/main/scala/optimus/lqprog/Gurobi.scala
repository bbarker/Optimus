package optimus.lqprog

/*
 *    /\\\\\
 *   /\\\///\\\
 *  /\\\/  \///\\\    /\\\\\\\\\     /\\\       /\\\
 *  /\\\      \//\\\  /\\\/////\\\ /\\\\\\\\\\\ \///    /\\\\\  /\\\\\     /\\\    /\\\  /\\\\\\\\\\
 *  \/\\\       \/\\\ \/\\\\\\\\\\ \////\\\////   /\\\  /\\\///\\\\\///\\\ \/\\\   \/\\\ \/\\\//////
 *   \//\\\      /\\\  \/\\\//////     \/\\\      \/\\\ \/\\\ \//\\\  \/\\\ \/\\\   \/\\\ \/\\\\\\\\\\
 *     \///\\\  /\\\    \/\\\           \/\\\_/\\  \/\\\ \/\\\  \/\\\  \/\\\ \/\\\   \/\\\ \////////\\\
 *        \///\\\\\/     \/\\\           \//\\\\\   \/\\\ \/\\\  \/\\\  \/\\\ \//\\\\\\\\\  /\\\\\\\\\\
 *           \/////       \///             \/////    \///  \///   \///   \///  \/////////   \//////////
 *
 * Copyright (C) 2014 Evangelos Michelioudakis, Anastasios Skarlatidis
 *
 * Optimus is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Optimus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/lgpl-3.0.en.html>.
 */

import optimus.algebra._
import optimus.lqprog.ProblemStatus.ProblemStatus
import gurobi._

/**
 * Gurobi solver.
 *
 * @author Vagelis Michelioudakis
 */
final class Gurobi extends AbstractMPSolver {

  var nbRows = 0
  var nbCols = 0
  var solution = Array[Double]()
  var objectiveValue = 0.0
  var status = ProblemStatus.NOT_SOLVED

  var env = new GRBEnv()
  var model = new GRBModel(env)

  /**
   * Problem builder, should configure the solver and append
   * mathematical model variables.
   *
   * @param nbRows rows in the model
   * @param nbCols number of variables in the model
   */
  def buildProblem(nbRows: Int, nbCols: Int) {

    println {
        """    _________                   ______ _____  """ + "\n" +
        """    __  ____/___  _________________  /____(_) """ + "\n" +
        """    _  / __ _  / / /_  ___/  __ \_  __ \_  /  """ + "\n" +
        """    / /_/ / / /_/ /_  /   / /_/ /  /_/ /  /   """ + "\n" +
        """    \____/  \__,_/ /_/    \____//_____//_/    """ + "\n"
    }

    println("Model gurobi: " + nbRows + "x" + nbCols)

    this.nbRows = nbRows
    this.nbCols = nbCols
    val cols = (1 to nbCols).toArray

    model.addVars(cols map (i => 0.0), cols map (i => GRB.INFINITY),
                  cols map (i => 0.0), cols map (i => GRB.CONTINUOUS), cols map ("x" + _))

    model.update()
  }

  /**
   * Get value of the variable in the specified position. Solution
   * should exist in order for a value to exist.
   *
   * @param colId position of the variable
   * @return the value of the variable in the solution
   */
  def getValue(colId: Int): Double = solution(colId)

  /**
   * Set bounds of variable in the specified position.
   *
   * @param colId position of the variable
   * @param lower domain lower bound
   * @param upper domain upper bound
   */
  def setBounds(colId: Int, lower: Double, upper: Double) {
    val GRBVar = model.getVar(colId)
    GRBVar.set(GRB.DoubleAttr.LB, lower)
    GRBVar.set(GRB.DoubleAttr.UB, upper)
  }

  /**
   * Set upper bound to unbounded (infinite)
   *
   * @param colId position of the variable
   */
  def setUnboundUpperBound(colId: Int) {
    model.getVar(colId).set(GRB.DoubleAttr.UB, GRB.INFINITY)
  }

  /**
   * Set lower bound to unbounded (infinite)
   *
   * @param colId position of the variable
   */
  def setUnboundLowerBound(colId: Int) {
    model.getVar(colId).set(GRB.DoubleAttr.LB, -GRB.INFINITY)
  }

  /**
   * Add objective expression to be optimized by the solver.
   *
   * @param objective the expression to be optimized
   * @param minimize flag for minimization instead of maximization
   */
  def addObjective(objective: Expression, minimize: Boolean) = {

    objective.getOrder match {
      case ExpressionOrder.HIGHER => throw new IllegalArgumentException("Higher than quadratic: " + objective)

      case ExpressionOrder.QUADRATIC =>
        val QExpression = new GRBQuadExpr
        for(term <- objective.terms) {
          if(term._1.length == 1) QExpression.addTerm(term._2, model.getVar(term._1(0).index))
          else QExpression.addTerm(term._2, model.getVar(term._1(0).index), model.getVar(term._1(1).index))
        }
        model.setObjective(QExpression, if (minimize) 1 else -1)

      case ExpressionOrder.LINEAR =>
        val LExpression = new GRBLinExpr
        val terms = objective.terms.toArray
        LExpression.addTerms(terms.map(pair => pair._2), terms.map(pair => model.getVar(pair._1(0).index)))
        model.setObjective(LExpression, if (minimize) 1 else -1)
    }

    model.update()
  }

  /**
   * Add a mathematical programming constraint to the solver.
   *
   * @param mpConstraint the mathematical programming constraint
   */
  def addConstraint(mpConstraint: MPConstraint) = {

    nbRows += 1

    val lhs = mpConstraint.constraint.lhs - mpConstraint.constraint.rhs
    val rhs = -lhs.constant
    val operator = mpConstraint.constraint.operator

    val GRBOperator = operator match {
      case ConstraintRelation.GE => GRB.GREATER_EQUAL
      case ConstraintRelation.LE => GRB.LESS_EQUAL
      case ConstraintRelation.EQ => GRB.EQUAL
    }

    lhs.getOrder match {
      case ExpressionOrder.HIGHER => throw new IllegalArgumentException("Higher than quadratic: " + lhs)

      case ExpressionOrder.QUADRATIC =>
        val QExpression = new GRBQuadExpr
        for(term <- lhs.terms) {
          if(term._1.length == 1) QExpression.addTerm(term._2, model.getVar(term._1(0).index))
          else QExpression.addTerm(term._2, model.getVar(term._1(0).index), model.getVar(term._1(1).index))
        }
        model.addQConstr(QExpression, GRBOperator, rhs, "")

      case ExpressionOrder.LINEAR =>
        val LExpression = new GRBLinExpr
        val terms = lhs.terms.toArray
        LExpression.addTerms(terms.map(pair => pair._2), terms.map(pair => model.getVar(pair._1(0).index)))
        model.addConstr(LExpression, GRBOperator, rhs, "")
    }
  }

  /**
   * Solve the problem.
   *
   * @return status code indicating the nature of the solution
   */
  def solveProblem(): ProblemStatus = {

    model.update()
    model.optimize()

    var optimizationStatus = model.get(GRB.IntAttr.Status)

    if (optimizationStatus == GRB.INF_OR_UNBD) {
      model.getEnv.set(GRB.IntParam.Presolve, 0)
      model.optimize()
      optimizationStatus = model.get(GRB.IntAttr.Status)
      ProblemStatus.UNBOUNDED
    }
    else if (optimizationStatus == GRB.OPTIMAL) {
      solution = Array.tabulate(nbCols)(col => model.getVar(col).get(GRB.DoubleAttr.X))
      objectiveValue = model.get(GRB.DoubleAttr.ObjVal)
      ProblemStatus.OPTIMAL
    }
    else if (optimizationStatus == GRB.INFEASIBLE) {
      println("Problem is infeasible!")
      model.computeIIS()
      ProblemStatus.INFEASIBLE
    }
    else if (optimizationStatus == GRB.UNBOUNDED) {
      println("Problem is unbounded!")
      ProblemStatus.UNBOUNDED
    }
    else {
      solution = Array.tabulate(nbCols)(col => model.getVar(col).get(GRB.DoubleAttr.X))
      println("Optimization stopped with status = " + optimizationStatus)
      ProblemStatus.SUBOPTIMAL
    }
  }

  /**
   * Release memory associated to the problem and the environment as well as
   * the gurobi license.
   */
  def release() = {
    model.dispose()
    env.release()
    env.dispose()
  }

  /**
   * Set a time limit for solver optimization. After the limit
   * is reached the solver stops running.
   *
   * @param limit the time limit
   */
  def setTimeout(limit: Int) {
    require(0 <= limit)
    model.getEnv.set(GRB.DoubleParam.TimeLimit, limit.toDouble)
  }
}
