/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.api.ml.regression;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.ml.param.DoubleParam;
import org.apache.spark.ml.param.IntParam;
import org.apache.spark.ml.param.ParamMap;
import org.apache.spark.ml.regression.Regressor;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.StructType;
import org.apache.sysml.api.DMLException;
import org.apache.sysml.api.MLContext;
import org.apache.sysml.api.MLOutput;
import org.apache.sysml.api.ml.functions.ConvertSingleColumnToString;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.instructions.spark.utils.RDDConverterUtils;
import org.apache.sysml.runtime.matrix.MatrixCharacteristics;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.MatrixIndexes;
import org.apache.sysml.api.ml.param.LinearRegressionCGParams;

import scala.Tuple2;

public class LinearRegressionCG extends Regressor<Vector, LinearRegressionCG, LinearRegressionModel>
		implements LinearRegressionCGParams {

	private static final long serialVersionUID = 2374652857525675368L;

	private SparkContext sc = null;
	private HashMap<String, String> cmdLineParams = new HashMap<String, String>();
	private HashMap<String, Tuple2<JavaPairRDD<MatrixIndexes, MatrixBlock>, MatrixCharacteristics>> results =
			new HashMap<String, Tuple2<JavaPairRDD<MatrixIndexes, MatrixBlock>, MatrixCharacteristics>>();

	private IntParam intercept = new IntParam(this, "icpt", "Value of intercept");
	private DoubleParam regParam = new DoubleParam(this, "reg", "Value of regularization parameter");
	private DoubleParam tol = new DoubleParam(this, "tol", "Value of tolerance");
	private IntParam maxIter = new IntParam(this, "maxi", "Maximum number of conjugate gradient iterations");

	private IntParam dfam = new IntParam(this,
			"dfam",
			"GLM distribution family: 1 = Power, 2 = Binomial, 3 = Multinomial Logit");
	private DoubleParam vpow = new DoubleParam(this, "vpow", "Power for Variance");
	private IntParam link = new IntParam(this,
			"link",
			"Link function code: 0 = canonical (depends on distribution), 1 = Power, 2 = Logit, 3 = Probit, 4 = Cloglog, 5 = Cauchit; ignored if Multinomial");
	private DoubleParam lpow = new DoubleParam(this, "lpow", "Power for Link function");
	private DoubleParam disp = new DoubleParam(this, "disp", "Dispersion Value");

	public LinearRegressionCG(SparkContext sc) throws DMLRuntimeException {
		this.sc = sc;
		setAllParameters(0, 0.000001f, 0.000001f, 0, 1, 0.0f, 0, 1.0f, 1.0f);
	}

	public LinearRegressionCG(SparkContext sc, int intercept, double regParam, double tol, int maxIter,
			int dfam, double vpow, int link, double lpow, double disp) throws DMLRuntimeException {
		this.sc = sc;
		setAllParameters(intercept, regParam, tol, maxIter, dfam, vpow, link, lpow, disp);
	}

	private void setAllParameters(
			int intercept,
			double regParam,
			double tol,
			int maxIter,
			int dfam,
			double vpow,
			int link,
			double lpow,
			double disp) {
		setDefault(intercept(), intercept);
		cmdLineParams.put(this.intercept.name(), Integer.toString(intercept));
		setDefault(regParam(), regParam);
		cmdLineParams.put(this.regParam.name(), Double.toString(regParam));
		setDefault(tol(), tol);
		cmdLineParams.put(this.tol.name(), Double.toString(tol));
		setDefault(maxIter(), maxIter);
		cmdLineParams.put(this.maxIter.name(), Integer.toString(maxIter));
		setDefault(dfam(), dfam);
		cmdLineParams.put(this.dfam.name(), Integer.toString(dfam));
		setDefault(vpow(), vpow);
		cmdLineParams.put(this.vpow.name(), Double.toString(vpow));
		setDefault(link(), link);
		cmdLineParams.put(this.link.name(), Integer.toString(link));
		setDefault(lpow(), lpow);
		cmdLineParams.put(this.lpow.name(), Double.toString(lpow));
		setDefault(disp(), disp);
		cmdLineParams.put(this.disp.name(), Double.toString(disp));
	}

	@Override
	public LinearRegressionCG copy(ParamMap paramMap) {
		try {
			String strIntercept = paramMap.getOrElse(intercept, getIntercept()).toString();
			String strRegParam = paramMap.getOrElse(regParam, getRegParam()).toString();
			String strTol = paramMap.getOrElse(tol, getTol()).toString();
			String strMaxIter = paramMap.getOrElse(maxIter, getMaxIter()).toString();
			String strDfam = paramMap.getOrElse(dfam, getDfam()).toString();
			String strVpow = paramMap.getOrElse(vpow, getVpow()).toString();
			String strLink = paramMap.getOrElse(link, getLink()).toString();
			String strLpow = paramMap.getOrElse(lpow, getLpow()).toString();
			String strDisp = paramMap.getOrElse(disp, getDisp()).toString();

			// Copy deals with command-line parameter of script
			// LinearRegCG.dml
			LinearRegressionCG lr = new LinearRegressionCG(sc,
					Integer.parseInt(strIntercept),
					Double.parseDouble(strRegParam),
					Double.parseDouble(strTol),
					Integer.parseInt(strMaxIter),
					Integer.parseInt(strDfam),
					Double.parseDouble(strVpow),
					Integer.parseInt(strLink),
					Double.parseDouble(strLpow),
					Double.parseDouble(strDisp));

			lr.cmdLineParams.put(intercept.name(), strIntercept);
			lr.cmdLineParams.put(regParam.name(), strRegParam);
			lr.cmdLineParams.put(tol.name(), strTol);
			lr.cmdLineParams.put(maxIter.name(), strMaxIter);
			lr.cmdLineParams.put(dfam.name(), strDfam);
			lr.cmdLineParams.put(vpow.name(), strVpow);
			lr.cmdLineParams.put(link.name(), strLink);
			lr.cmdLineParams.put(lpow.name(), strLpow);
			lr.cmdLineParams.put(disp.name(), strDisp);
			lr.setFeaturesCol(getFeaturesCol());
			lr.setLabelCol(getLabelCol());

			return lr;
		} catch (DMLRuntimeException e) {
			e.printStackTrace();
		}

		return null;
	}

	@Override
	public String uid() {
		return Long.toString(serialVersionUID);
	}

	@Override
	public StructType validateAndTransformSchema(StructType arg0, boolean arg1, DataType arg2) {
		return null;
	}

	public LinearRegressionCG setIntercept(int value) {
		cmdLineParams.put(intercept.name(), Integer.toString(value));
		return (LinearRegressionCG) setDefault(intercept, value);
	}

	public int getIntercept() {
		return Integer.parseInt(cmdLineParams.get(intercept.name()));
	}

	public IntParam intercept() {
		return intercept;
	}

	public LinearRegressionCG setRegParam(double value) {
		cmdLineParams.put(regParam.name(), Double.toString(value));
		return (LinearRegressionCG) setDefault(regParam, value);
	}

	@Override
	public double getRegParam() {
		return Double.parseDouble(cmdLineParams.get(regParam.name()));
	}

	@Override
	public void org$apache$spark$ml$param$shared$HasRegParam$_setter_$regParam_$eq(DoubleParam arg0) {

	}

	@Override
	public DoubleParam regParam() {
		return regParam;
	}

	public LinearRegressionCG setMaxIter(int value) {
		cmdLineParams.put(maxIter.name(), Integer.toString(value));
		return (LinearRegressionCG) setDefault(maxIter, value);
	}

	@Override
	public int getMaxIter() {
		return Integer.parseInt(cmdLineParams.get(maxIter.name()));
	}

	@Override
	public IntParam maxIter() {
		return maxIter;
	}

	@Override
	public void org$apache$spark$ml$param$shared$HasMaxIter$_setter_$maxIter_$eq(IntParam arg0) {

	}

	public LinearRegressionCG setTol(double value) {
		cmdLineParams.put(tol.name(), Double.toString(value));
		return (LinearRegressionCG) setDefault(tol, value);
	}

	@Override
	public double getTol() {
		return Double.parseDouble(cmdLineParams.get(tol.name()));
	}

	@Override
	public void org$apache$spark$ml$param$shared$HasTol$_setter_$tol_$eq(DoubleParam arg0) {

	}

	@Override
	public DoubleParam tol() {
		return tol;
	}

	public LinearRegressionCG setDfam(int value) {
		cmdLineParams.put(dfam.name(), Integer.toString(value));
		return (LinearRegressionCG) setDefault(dfam, value);
	}

	@Override
	public int getDfam() {
		return Integer.parseInt(cmdLineParams.get(dfam.name()));
	}

	@Override
	public IntParam dfam() {
		return dfam;
	}

	public LinearRegressionCG setVpow(double value) {
		cmdLineParams.put(vpow.name(), Double.toString(value));
		return (LinearRegressionCG) setDefault(vpow, value);
	}

	@Override
	public double getVpow() {
		return Double.parseDouble(cmdLineParams.get(vpow.name()));
	}

	@Override
	public DoubleParam vpow() {
		return vpow;
	}

	public LinearRegressionCG setLink(int value) {
		cmdLineParams.put(link.name(), Integer.toString(value));
		return (LinearRegressionCG) setDefault(link, value);
	}

	@Override
	public int getLink() {
		return Integer.parseInt(cmdLineParams.get(link.name()));
	}

	@Override
	public IntParam link() {
		return link;
	}

	public LinearRegressionCG setLpow(double value) {
		cmdLineParams.put(lpow.name(), Double.toString(value));
		return (LinearRegressionCG) setDefault(vpow, value);
	}

	@Override
	public double getLpow() {
		return Double.parseDouble(cmdLineParams.get(vpow.name()));
	}

	@Override
	public DoubleParam lpow() {
		return lpow;
	}

	public LinearRegressionCG setDisp(double value) {
		cmdLineParams.put(disp.name(), Double.toString(value));
		return (LinearRegressionCG) setDefault(disp, value);
	}

	@Override
	public double getDisp() {
		return Double.parseDouble(cmdLineParams.get(disp.name()));
	}

	@Override
	public DoubleParam disp() {
		return disp;
	}

	@Override
	public LinearRegressionModel train(DataFrame df) {
		MLContext ml = null;
		MLOutput out = null;

		try {
			ml = new MLContext(this.sc);
		} catch (DMLRuntimeException e1) {
			e1.printStackTrace();
			return null;
		}

		// Convert input data to format that SystemML accepts
		MatrixCharacteristics mcXin = new MatrixCharacteristics();
		JavaPairRDD<MatrixIndexes, MatrixBlock> Xin;
		Xin = RDDConverterUtils.dataFrameToBinaryBlock(new JavaSparkContext(this.sc),
				df.select(getFeaturesCol()),
				mcXin,
				false,
				true);

		JavaRDD<String> yin =
				df.select(getLabelCol()).rdd().toJavaRDD().map(new ConvertSingleColumnToString());

		try {
			// Register the input/output variables of script
			// 'LinearRegCG.dml'
			ml.registerInput("X", Xin, mcXin);
			ml.registerInput("y", yin, "csv");
			ml.registerOutput("beta_out");

//			String systemmlHome = System.getenv("SYSTEMML_HOME");
//			if (systemmlHome == null) {
//				System.err.println("ERROR: The environment variable SYSTEMML_HOME is not set.");
//				return null;
//			}

			// Or add ifdef in LinearRegCG.dml
			cmdLineParams.put("X", " ");
			cmdLineParams.put("Y", " ");
			cmdLineParams.put("B", " ");

//			String dmlFilePath = systemmlHome + File.separator + "scripts" + File.separator + "algorithms" + File.separator + "LinearRegCG.dml";
			String dmlFilePath = "scripts" + File.separator + "algorithms" + File.separator + "LinearRegCG.dml";

			synchronized (MLContext.class) {
				// static synchronization is necessary before
				// execute call
				out = ml.execute(dmlFilePath, cmdLineParams);
			}

			results.put("B_full",
					new Tuple2<JavaPairRDD<MatrixIndexes, MatrixBlock>, MatrixCharacteristics>(
							out.getBinaryBlockedRDD("beta_out"),
							out.getMatrixCharacteristics("beta_out")));

			return new LinearRegressionModel(results,
					sc,
					cmdLineParams,
					getFeaturesCol(),
					getLabelCol()).setParent(this);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (DMLRuntimeException e) {
			throw new RuntimeException(e);
		} catch (DMLException e) {
			throw new RuntimeException(e);
		}
	}
}