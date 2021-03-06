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
package org.apache.sysml.runtime.matrix.data;

import java.util.ArrayList;
import java.util.concurrent.Callable;

import org.apache.sysml.api.DMLScript;
import org.apache.sysml.utils.NativeHelper;

/**
 * This class contains the set of operators used for performing conv2d
 */
public class LibMatrixDNNConv2dHelper {

	/**
	 * Performs convolution via: partialCopy1(filter %*% im2col(input)) = output.
	 * This operator has less memory pressure than LoopedIm2ColConv2dAllChannels.
	 */
	public static class LoopedIm2ColConv2dOneChannel implements Callable<Long> 
	{
		public int _rl; public int _ru; 
		private final ConvolutionParameters _params; ArrayList<MatrixBlock> _filters;
		public LoopedIm2ColConv2dOneChannel(int rl, int ru, ConvolutionParameters params, ArrayList<MatrixBlock> filters) {
			_rl = rl; _ru = ru;
			_params = params; 
			_filters = filters;
		}
		
		@Override
		public Long call() throws Exception {
			int PQ = _params.P*_params.Q; int K = _params.K;
			int RS = _params.R*_params.S;
			MatrixBlock im2ColOutBlock = new MatrixBlock(RS, PQ, false);
			LibMatrixDNNIm2ColHelper.Im2colWorker im2ColWorker = LibMatrixDNNIm2ColHelper.Im2colWorker.getWorker( _params.input1, im2ColOutBlock, _params, false);
			long time1 = 0; long time2 = 0;
			for(int n = _rl; n < _ru; n++)  {
				for(int c = 0; c < _params.C; c++)  {
					// im2col(input) => _im2ColOutBlock
					long t1 = DMLScript.STATISTICS && LibMatrixDNN.DISPLAY_STATISTICS ? System.nanoTime() : 0;
					im2ColWorker.execute(n, c);
					long t2 = DMLScript.STATISTICS && LibMatrixDNN.DISPLAY_STATISTICS ? System.nanoTime() : 0;
					
					// filter %*% _im2ColOutBlock => matMultOutBlock
					MatrixBlock matMultOutBlock = new MatrixBlock(K, PQ, false);
					LibMatrixDNNHelper.singleThreadedMatMult(_filters.get(c), im2ColOutBlock, matMultOutBlock, false, true, _params);
					long t3 = DMLScript.STATISTICS && LibMatrixDNN.DISPLAY_STATISTICS ? System.nanoTime() : 0;
					
					if(DMLScript.STATISTICS && LibMatrixDNN.DISPLAY_STATISTICS) {
						time1 += t2 - t1;
						time2 += t3 - t2;
					}
					
					// Add the matrix matMultOutBlock of shape [K X PQ] to params.output.denseBlock + destPos
					add(matMultOutBlock, _params.output.getDenseBlock(), n*K*PQ, K, PQ);
				}
				// Add bias to current row if necessary, always dense
				if(_params.bias != null)
					LibMatrixDNNHelper.addBias(n, _params.output.getDenseBlock(), _params.bias.getDenseBlock(), K, PQ);
			}
			if(DMLScript.STATISTICS && LibMatrixDNN.DISPLAY_STATISTICS) {
				LibMatrixDNN.loopedConvIm2ColTime.addAndGet(time1);
				LibMatrixDNN.loopedConvMatMultTime.addAndGet(time2);
			}
			
			//multi-threaded nnz maintenance of current working set
			return _params.output.recomputeNonZeros(_rl, _ru-1);
		}
		
		// Copy the matrix src of shape [K X PQ] to params.output.denseBlock + destPos
		private static void add(MatrixBlock src, double [] dest, int destPos, int K, int PQ) {
			// Copying is required as LibMatrixMult.matrixMult (and/or Java) is not pointer aware.
			// This is not required in Native implementation
			if(!src.isEmptyBlock()) {
				if(src.isInSparseFormat()) {
					// Copy the sparse matrix matMultOutBlock of shape [K X PQ] to 
					// params.output.denseBlock + destPos
					for(int k = 0; k < src.getNumRows(); k++) {
						if( !src.sparseBlock.isEmpty(k) ) {
							int apos = src.sparseBlock.pos(k);
							int alen = src.sparseBlock.size(k);
							int[] aix = src.sparseBlock.indexes(k);
							double[] avals = src.sparseBlock.values(k);
							int desPosK = destPos + k*PQ;
							for(int j = apos; j < apos+alen; j++) {
								int pqIndex = aix[j];
								dest[desPosK + pqIndex ] += avals[j];
							}
						}
					}
				}
				else {
					LibMatrixMult.vectAdd(src.denseBlock, dest, 0, destPos, K*PQ);
				}
			}
		}
	}	
	
	/**
	 * Performs convolution via: partialCopy1(filter %*% im2col(input)) = output
	 */
	public static class LoopedIm2ColConv2dAllChannels implements Callable<Long> 
	{
		public int _rl; public int _ru; 
		private final ConvolutionParameters _params;
		public LoopedIm2ColConv2dAllChannels(int rl, int ru, ConvolutionParameters params) {
			_rl = rl; _ru = ru;
			_params = params;
		}

		@Override
		public Long call() throws Exception {
			int PQ = _params.P*_params.Q; int K = _params.K; int CRS = _params.C*_params.R*_params.S;
			MatrixBlock outIm2col = new MatrixBlock(CRS, PQ, false);
			MatrixBlock outMM = new MatrixBlock(K, PQ, false);
			LibMatrixDNNIm2ColHelper.Im2colWorker im2ColWorker = 
					LibMatrixDNNIm2ColHelper.Im2colWorker.getWorker( _params.input1, outIm2col, _params, true);
			long time1 = 0; long time2 = 0;
			for(int n = _rl; n < _ru; n++)  {
				// im2col(input) => _im2ColOutBlock
				long t1 = DMLScript.STATISTICS && LibMatrixDNN.DISPLAY_STATISTICS ? System.nanoTime() : 0;
				im2ColWorker.execute(n);
				long t2 = DMLScript.STATISTICS && LibMatrixDNN.DISPLAY_STATISTICS ? System.nanoTime() : 0;
				
				// filter %*% _im2ColOutBlock => matMultOutBlock
				outMM.reset(outMM.rlen, outMM.clen, false);
				LibMatrixDNNHelper.singleThreadedMatMult(_params.input2, outIm2col, outMM, false, true, _params);
				long t3 = DMLScript.STATISTICS && LibMatrixDNN.DISPLAY_STATISTICS ? System.nanoTime() : 0;
				
				if(DMLScript.STATISTICS && LibMatrixDNN.DISPLAY_STATISTICS) {
					time1 += t2 - t1;
					time2 += t3 - t2;
				}
				
				// Copy the matrix matMultOutBlock of shape [K X PQ] to params.output.denseBlock + destPos
				partialCopy1(outMM, _params.output.getDenseBlock(), n*K*PQ, K, PQ);
				
				// Add bias to current row if necessary, always dense
				if(_params.bias != null)
					LibMatrixDNNHelper.addBias(n, _params.output.getDenseBlock(), _params.bias.getDenseBlock(), K, PQ);
			}
			
			if(DMLScript.STATISTICS && LibMatrixDNN.DISPLAY_STATISTICS) {
				LibMatrixDNN.loopedConvIm2ColTime.addAndGet(time1);
				LibMatrixDNN.loopedConvMatMultTime.addAndGet(time2);
			}
			
			//multi-threaded nnz maintenance of current working set
			return _params.output.recomputeNonZeros(_rl, _ru-1);
		}
		
		// Copy the matrix src of shape [K X PQ] to params.output.denseBlock + destPos
		private static void partialCopy1(MatrixBlock src, double [] dest, int destPos, int K, int PQ) {
			// Copying is required as LibMatrixMult.matrixMult (and/or Java) is not pointer aware.
			// This is not required in Native implementation
			if( src.isEmptyBlock() )
				return;
			if(src.isInSparseFormat()) {
				SparseBlock sblock = src.sparseBlock;
				for(int k = 0; k < src.getNumRows(); k++) {
					if( sblock.isEmpty(k) ) continue;
					int apos = sblock.pos(k);
					int alen = sblock.size(k);
					int[] aix = sblock.indexes(k);
					double[] avals = sblock.values(k);
					int desPosK = destPos + k*PQ;
					for(int j = apos; j < apos+alen; j++)
						dest[desPosK+aix[j]] = avals[j];
				}
			}
			else 
				System.arraycopy(src.denseBlock, 0, dest, destPos, K * PQ);
		}
	}
	
	
	/**
	 * This operator is used only if native is enabled, filter is dense and input is sparse
	 */
	public static class SparseNativeConv2d implements Callable<Long> 
	{
		public int _rl; public int _ru; 
		private final ConvolutionParameters _params;
		public SparseNativeConv2d(int rl, int ru, ConvolutionParameters params) {
			_rl = rl; _ru = ru;
			_params = params;
		}

		@Override
		public Long call() throws Exception {
			int KPQ = _params.K*_params.P*_params.Q;
			double[] temp = new double[KPQ];
			for(int n = _rl; n < _ru; n++)  {
				if( !_params.input1.getSparseBlock().isEmpty(n) ) {
					int apos = _params.input1.getSparseBlock().pos(n);
					int alen = _params.input1.getSparseBlock().size(n);
					int[] aix = _params.input1.getSparseBlock().indexes(n);
					double[] avals = _params.input1.getSparseBlock().values(n);
					NativeHelper.conv2dSparse(apos, alen, aix, avals, _params.input2.getDenseBlock(), temp, 
							1, _params.C, _params.H, _params.W, _params.K, _params.R, _params.S, 
							_params.stride_h, _params.stride_w, _params.pad_h, _params.pad_w, _params.P, _params.Q, 1);
					System.arraycopy(temp, 0, _params.output.denseBlock, n*KPQ, KPQ);
				}
			}
			//multi-threaded nnz maintenance of current working set
			return _params.output.recomputeNonZeros(_rl, _ru-1);
		}
	}
}
