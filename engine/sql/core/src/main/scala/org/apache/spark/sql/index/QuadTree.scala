/*
 * Copyright 2016 by Simba Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package org.apache.spark.sql.index

import org.apache.spark.sql.spatial.{MBR, Point, Shape}

import scala.collection._
import scala.util.Random

/**
  * Created by gefei on 16-6-10.
  */

import collection.mutable

case class QuadTreeNode(x_low: Double, y_low: Double, x_high: Double, y_high: Double,
  var children: Array[QuadTreeNode], var objects: Array[(Double, Double, Int)]){
  private val center_x = (x_low + x_high) / 2
  private val center_y = (y_low + y_high) / 2

  def whichChild(obj: (Double, Double)): Int = {
    (if (obj._1 > center_x) 1 else 0) + (if (obj._2 > center_y) 2 else 0)
  }
  def makeChildren(grouped: Map[Int, Array[(Double, Double, Int)]]): Unit = {
    children = Array(
      QuadTreeNode(x_low, y_low, center_x, center_y, null, grouped.getOrElse(0, Array())),
      QuadTreeNode(center_x, y_low, x_high, center_y, null, grouped.getOrElse(1, Array())),
      QuadTreeNode(x_low, center_y, center_x, y_high, null, grouped.getOrElse(2, Array())),
      QuadTreeNode(center_x, center_y, x_high, y_high, null, grouped.getOrElse(3, Array()))
    )
  }
}

case class QuadTree(root: QuadTreeNode) extends Index with Serializable{
  val MAX_NODES = 3
  def bulkload(): QuadTreeNode = this.bulkload(root)

  private def bulkload(root: QuadTreeNode): QuadTreeNode = {
    val grouped = root.objects.groupBy(obj => root.whichChild(obj._1, obj._2))
    root.makeChildren(grouped)
    for (child <- root.children) {
      if (child.objects.length >= MAX_NODES) bulkload(child)
    }
    root.objects = null
    root
  }

  def range(x_min: Double, y_min: Double,
            x_max: Double, y_max: Double): Array[(Double, Double, Int)] = {
    val res = new mutable.ArrayBuffer[(Double, Double, Int)]()
    res ++= searchRecur(root, x_min, y_min, x_max, y_max)
    res.toArray
  }

  // interface same with RTree
  def range(query: MBR): Array[(Point, Int)] = {
    val temp_result = this.range(query.low.coord(0), query.low.coord(1),
      query.high.coord(0), query.high.coord(1))
    temp_result.map(item => (Point(Array(item._1, item._2)), item._3))
  }

  def searchRecur(node: QuadTreeNode, x_min: Double, y_min: Double,
                  x_max: Double, y_max: Double): mutable.ArrayBuffer[(Double, Double, Int)] = {
    val res = new mutable.ArrayBuffer[(Double, Double, Int)]()
    if (node.objects == null) for (child <- node.children) res ++= searchRecur(child,
      x_min: Double, y_min: Double, x_max: Double, y_max: Double)
    else {
      res ++= node.objects.filter(item => item._1 >= x_min
        && item._1 <= x_max && item._2 >= y_min && item._2 <= y_max)
    }
    res
  }
}

object QuadTree{
  def apply(entries: Array[(Point, Int)]): QuadTree = {
    this(entries.map(item => (item._1.coord(0), item._1.coord(1), item._2)))
  }

  def apply(entries: Array[(Double, Double, Int)]): QuadTree = {
    // collect the border of the total entries
    val (x_min, y_min, x_max, y_max) = entries.aggregate(
      (Double.MaxValue, Double.MaxValue, Double.MinValue, Double.MinValue)
    )((a: (Double, Double, Double, Double), b: (Double, Double, Int)) =>
      (math.min(a._1, b._1), math.min(a._2, b._2), math.max(a._3, b._1), math.max(a._4, b._2)),
      (a: (Double, Double, Double, Double), b: (Double, Double, Double, Double)) =>
      (math.min(a._1, b._1), math.min(a._2, b._2), math.max(a._3, b._3), math.max(a._4, b._4)))
    val root = new QuadTreeNode(x_min, y_min, x_max, y_max, null, entries)
    val quadTree = new QuadTree(root)
    quadTree.bulkload()
    quadTree
  }
}