/*
 * (c) Copyright Christian P. Fries, Germany. All rights reserved. Contact: email@christian-fries.de.
 *
 * Created on 09.02.2006
 */
package net.finmath.montecarlo.cuda;

import static jcuda.driver.JCudaDriver.cuCtxCreate;
import static jcuda.driver.JCudaDriver.cuCtxSynchronize;
import static jcuda.driver.JCudaDriver.cuDeviceGet;
import static jcuda.driver.JCudaDriver.cuInit;
import static jcuda.driver.JCudaDriver.cuLaunchKernel;
import static jcuda.driver.JCudaDriver.cuMemcpyDtoH;
import static jcuda.driver.JCudaDriver.cuModuleGetFunction;
import static jcuda.driver.JCudaDriver.cuModuleLoad;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntToDoubleFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.DoubleStream;

import jcuda.LogLevel;
import jcuda.Pointer;
import jcuda.Sizeof;
import jcuda.driver.CUcontext;
import jcuda.driver.CUdevice;
import jcuda.driver.CUdeviceptr;
import jcuda.driver.CUfunction;
import jcuda.driver.CUmodule;
import jcuda.driver.JCudaDriver;
import net.finmath.functions.DoubleTernaryOperator;
import net.finmath.montecarlo.RandomVariableFromFloatArray;
import net.finmath.stochastic.RandomVariable;

/**
 * The class RandomVariableFromDoubleArray represents a random variable being the evaluation of a stochastic process
 * at a certain time within a Monte-Carlo simulation.
 * It is thus essentially a vector of floating point numbers - the realizations - together with a double - the time.
 * The index of the vector represents path.
 * The class may also be used for non-stochastic quantities which may potentially be stochastic
 * (e.g. volatility). If only non-stochastic random variables are involved in an operation the class uses
 * optimized code.
 *
 * Accesses performed exclusively through the interface
 * <code>RandomVariable</code> is thread safe (and does not mutate the class).
 *
 * This implementation uses floats for the realizations (consuming less memory compared to using doubles). However,
 * the calculation of the average is performed using double precision.
 *
 * @author Christian Fries
 * @version 1.8
 */
public class RandomVariableCuda implements RandomVariable {

	private static class DeviceMemoryPool {
		private final static Map<Integer, ReferenceQueue<RandomVariableCuda>>		vectorsToRecycleReferenceQueueMap	= new ConcurrentHashMap<Integer, ReferenceQueue<RandomVariableCuda>>();
		private final static Map<WeakReference<RandomVariableCuda>, CUdeviceptr>	vectorsInUseReferenceMap			= new ConcurrentHashMap<WeakReference<RandomVariableCuda>, CUdeviceptr>();
		private final static float	vectorsRecyclerPercentageFreeToStartGC		= 0.10f;		// should be set by monitoring GPU mem
		private final static float	vectorsRecyclerPercentageFreeToWaitForGC	= 0.05f;		// should be set by monitoring GPU mem
		private final static long	vectorsRecyclerMaxTimeOutMillis			= 300;

		// Thread to collect weak references - will be worked on for a future version.
		/* *
		static {
			new Thread(new Runnable() {
				@Override
				public void run() {
					while(true) {
						System.gc();
						try {
							Thread.sleep(10);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}).start();
		}
		* */

		public synchronized void manage(CUdeviceptr cuDevicePtr, RandomVariableCuda wrapper) {
			if(logger.isLoggable(Level.FINEST)) logger.finest("Managing" + cuDevicePtr + " with " + wrapper + ". Size of reference map " + vectorsInUseReferenceMap.size());
			int size = wrapper.size();

			ReferenceQueue<RandomVariableCuda> vectorsToRecycleReferenceQueue = vectorsToRecycleReferenceQueueMap.get(new Integer(size));
			if(vectorsToRecycleReferenceQueue == null) {
				logger.fine("Creating reference queue for vector size " + size);
				vectorsToRecycleReferenceQueueMap.put(new Integer(size), vectorsToRecycleReferenceQueue = new ReferenceQueue<RandomVariableCuda>());
			}
			// Manage CUdeviceptr
			WeakReference<RandomVariableCuda> reference = new WeakReference<RandomVariableCuda>(wrapper, vectorsToRecycleReferenceQueue);
			vectorsInUseReferenceMap.put(reference, cuDevicePtr);
			if(logger.isLoggable(Level.FINEST)) logger.finest("Created weak reference " + reference + ". Size of reference map " + vectorsInUseReferenceMap.size());
		}

		public synchronized CUdeviceptr getCUdeviceptr(final long size) {
			if(logger.isLoggable(Level.FINEST)) {
				StringBuilder stringBuilder = new StringBuilder();
				stringBuilder.append("Memory pool stats: ");
				stringBuilder.append("  vector sizes: ");
				for(Map.Entry<Integer, ReferenceQueue<RandomVariableCuda>> entry : vectorsToRecycleReferenceQueueMap.entrySet()) {
					stringBuilder.append("    " + entry.getKey());
				}
				stringBuilder.append("  total number of vectors: " + vectorsInUseReferenceMap.size());
				logger.finest(stringBuilder.toString());
			}

			CUdeviceptr cuDevicePtr = null;

			// Check for object to recycle
			ReferenceQueue<RandomVariableCuda> vectorsToRecycleReferenceQueue = vectorsToRecycleReferenceQueueMap.get(new Integer((int)size));
			if(vectorsToRecycleReferenceQueue == null) {
				logger.info("Creating reference queue for vector size " + size);
				vectorsToRecycleReferenceQueueMap.put(new Integer((int)size), vectorsToRecycleReferenceQueue = new ReferenceQueue<RandomVariableCuda>());
			}

			Reference<? extends RandomVariableCuda> reference = vectorsToRecycleReferenceQueue.poll();
			if(reference != null) {
				cuDevicePtr = vectorsInUseReferenceMap.remove(reference);
				logger.finest("Recycling (1) device pointer " + cuDevicePtr + " from " + reference);
			}
			else {

				float deviceFreeMemPercentage = getDeviceFreeMemPercentage();
				logger.finest("Device free memory " + deviceFreeMemPercentage + "%");

				// No pointer found, try GC if we are above a critical level
				if(reference == null && deviceFreeMemPercentage < vectorsRecyclerPercentageFreeToStartGC) {
					try {
						System.gc();
						reference = vectorsToRecycleReferenceQueue.remove(1);
					} catch (IllegalArgumentException | InterruptedException e) {}
				}

				// Wait for GC
				if(reference == null && deviceFreeMemPercentage < vectorsRecyclerPercentageFreeToWaitForGC) {

					/*
					 * Try to obtain a reference after GC, retry with waits for 1 ms, 10 ms, 100 ms, ...
					 */
					System.gc();
					long timeOut = 1;
					while(reference == null && timeOut < vectorsRecyclerMaxTimeOutMillis) {
						try {
							reference = vectorsToRecycleReferenceQueue.remove(timeOut);
							timeOut *= 4;
						} catch (IllegalArgumentException | InterruptedException e) {}
					}

					if(reference != null) {
						logger.finest("Recycling (2) device pointer " + cuDevicePtr + " from " + reference);
						cuDevicePtr = vectorsInUseReferenceMap.remove(reference);
					}
					else {
						// Still no pointer found for requested size, consider cleaning all (also other sizes)
						logger.info("Last resort: Cleaning all unused vectors on device.");
						clean();
					}
				}
			}

			if(cuDevicePtr != null) return cuDevicePtr;

			// Still no pointer found, create new one
			try {
				cuDevicePtr =
						deviceExecutor.submit(new Callable<CUdeviceptr>() { public CUdeviceptr call() {
							CUdeviceptr cuDevicePtr = new CUdeviceptr();
							int succ = JCudaDriver.cuMemAlloc(cuDevicePtr, size * Sizeof.FLOAT);
							if(succ != 0) {
								cuDevicePtr = null;
								String[] cudaErrorName = new String[1];
								JCudaDriver.cuGetErrorName(succ, cudaErrorName);
								String[] cudaErrorDescription = new String[1];
								JCudaDriver.cuGetErrorString(succ, cudaErrorDescription);

								logger.warning("Failed creating device vector "+ cuDevicePtr + " with size=" + size + " with error "+ cudaErrorName + ": " + cudaErrorDescription);
							}
							else {
								logger.finest("Creating device vector "+ cuDevicePtr + " with size=" + size);
							}
							cuCtxSynchronize();
							return cuDevicePtr;
						}}).get();
			} catch (InterruptedException | ExecutionException e) {
				logger.severe("Failed to allocate device vector with size=" + size);
			}

			if(cuDevicePtr == null) {
				logger.severe("Failed to allocate device vector with size=" + size);
				throw new OutOfMemoryError("Failed to allocate device vector with size=" + size);
			}

			return cuDevicePtr;
		}

		public synchronized void clean() {
			// Clean up all remaining pointers
			for(ReferenceQueue<RandomVariableCuda> vectorsToRecycleReferenceQueue : vectorsToRecycleReferenceQueueMap.values()) {
				Reference<? extends RandomVariableCuda> reference;
				while((reference = vectorsToRecycleReferenceQueue.poll()) != null) {
					final CUdeviceptr cuDevicePtr = vectorsInUseReferenceMap.remove(reference);
					logger.finest("Freeing device pointer " + cuDevicePtr + " from " + reference);
					try {
						deviceExecutor.submit(new Runnable() { public void run() {
							cuCtxSynchronize();
							JCudaDriver.cuMemFree(cuDevicePtr);
						}}).get();
					} catch (InterruptedException | ExecutionException e) {
						logger.severe("Unable to free pointer " + cuDevicePtr + " from " + reference);
						throw new RuntimeException(e.getCause());
					}
				}
			}
		}

	}

	private static DeviceMemoryPool deviceMemoryPool = new DeviceMemoryPool();


	private static final long serialVersionUID = 7620120320663270600L;

	private final double      time;	                // Time (filtration)

	private static final int typePriorityDefault = 20;

	private final int typePriority;

	// Data model for the stochastic case (otherwise null)
	private final CUdeviceptr	realizations;           // Realizations
	private final long			size;

	// Data model for the non-stochastic case (if realizations==null)
	private final double      valueIfNonStochastic;


	private final static Logger logger = Logger.getLogger("net.finmath");

	private final static ExecutorService deviceExecutor = Executors.newSingleThreadExecutor();
	public final static CUdevice device = new CUdevice();
	public final static CUcontext context = new CUcontext();
	public final static CUmodule module = new CUmodule();

	private final static CUfunction capByScalar = new CUfunction();
	private final static CUfunction floorByScalar = new CUfunction();
	private final static CUfunction addScalar = new CUfunction();
	private final static CUfunction subScalar = new CUfunction();
	private final static CUfunction busScalar = new CUfunction();
	private final static CUfunction multScalar = new CUfunction();
	private final static CUfunction divScalar = new CUfunction();
	private final static CUfunction vidScalar = new CUfunction();
	private final static CUfunction cuPow = new CUfunction();
	private final static CUfunction cuSqrt = new CUfunction();
	private final static CUfunction cuExp = new CUfunction();
	private final static CUfunction cuLog = new CUfunction();
	private final static CUfunction invert = new CUfunction();
	private final static CUfunction cuAbs = new CUfunction();
	private final static CUfunction cap = new CUfunction();
	private final static CUfunction cuFloor = new CUfunction();
	private final static CUfunction add = new CUfunction();
	private final static CUfunction sub = new CUfunction();
	private final static CUfunction mult = new CUfunction();
	private final static CUfunction cuDiv = new CUfunction();
	private final static CUfunction accrue = new CUfunction();
	private final static CUfunction discount = new CUfunction();
	private final static CUfunction addProduct = new CUfunction();
	private final static CUfunction addProduct_vs = new CUfunction();		// add the product of a vector and a scalar
	private final static CUfunction reducePartial = new CUfunction();

	private final static int reduceGridSize = 1024;

	// Initalize cuda
	static {
		synchronized (deviceMemoryPool) {
			// Enable exceptions and omit all subsequent error checks
			JCudaDriver.setExceptionsEnabled(true);
			JCudaDriver.setLogLevel(LogLevel.LOG_DEBUG);

			// Create the PTX file by calling the NVCC
			String ptxFileName = null;
			try {
				URL cuFileURL = RandomVariableCuda.class.getClassLoader().getResource("net/finmath/montecarlo/RandomVariableCudaKernel.cu");
				ptxFileName = net.finmath.jcuda.JCudaUtils.preparePtxFile(cuFileURL);
			} catch (IOException | URISyntaxException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			final String ptxFileName2 = ptxFileName;
			deviceExecutor.submit(new Runnable() { public void run() {
				// Initialize the driver and create a context for the first device.
				cuInit(0);
				cuDeviceGet(device, 0);
				cuCtxCreate(context, jcuda.driver.CUctx_flags.CU_CTX_SCHED_BLOCKING_SYNC, device);

				// Load the ptx file.
				cuModuleLoad(module, ptxFileName2);

				// Obtain a function pointers
				cuModuleGetFunction(capByScalar, module, "capByScalar");
				cuModuleGetFunction(floorByScalar, module, "floorByScalar");
				cuModuleGetFunction(addScalar, module, "addScalar");
				cuModuleGetFunction(subScalar, module, "subScalar");
				cuModuleGetFunction(busScalar, module, "busScalar");
				cuModuleGetFunction(multScalar, module, "multScalar");
				cuModuleGetFunction(divScalar, module, "divScalar");
				cuModuleGetFunction(vidScalar, module, "vidScalar");
				cuModuleGetFunction(cuPow, module, "cuPow");
				cuModuleGetFunction(cuSqrt, module, "cuSqrt");
				cuModuleGetFunction(cuExp, module, "cuExp");
				cuModuleGetFunction(cuLog, module, "cuLog");
				cuModuleGetFunction(invert, module, "invert");
				cuModuleGetFunction(cuAbs, module, "cuAbs");
				cuModuleGetFunction(cap, module, "cap");
				cuModuleGetFunction(cuFloor, module, "cuFloor");
				cuModuleGetFunction(add, module, "add");
				cuModuleGetFunction(sub, module, "sub");
				cuModuleGetFunction(mult, module, "mult");
				cuModuleGetFunction(cuDiv, module, "cuDiv");
				cuModuleGetFunction(accrue, module, "accrue");
				cuModuleGetFunction(discount, module, "discount");
				cuModuleGetFunction(addProduct, module, "addProduct");
				cuModuleGetFunction(addProduct_vs, module, "addProduct_vs");
				cuModuleGetFunction(reducePartial, module, "reducePartial");
			}});
		}
	}

	public static RandomVariableCuda of(double time, CUdeviceptr realizations, long size) {
		RandomVariableCuda randomVariableCuda = new RandomVariableCuda(time, realizations, size);
		deviceMemoryPool.manage(realizations, randomVariableCuda);
		return randomVariableCuda;
	}

	private RandomVariableCuda(double time, CUdeviceptr realizations, long size) {
		this.time = time;
		this.realizations = realizations;
		this.size = size;
		this.valueIfNonStochastic = Double.NaN;
		this.typePriority = typePriorityDefault;
	}

	/**
	 * Create a non stochastic random variable, i.e. a constant.
	 *
	 * @param value the value, a constant.
	 */
	public RandomVariableCuda(double value) {
		this(-Double.MAX_VALUE, value);
	}

	/**
	 * Create a non stochastic random variable, i.e. a constant.
	 *
	 * @param time the filtration time, set to 0.0 if not used.
	 * @param value the value, a constant.
	 */
	public RandomVariableCuda(double time, double value) {
		super();
		this.time = time;
		this.realizations = null;
		this.size = 1;
		this.valueIfNonStochastic = value;
		this.typePriority = typePriorityDefault;
	}

	/**
	 * Create a stochastic random variable.
	 *
	 * @param time the filtration time, set to 0.0 if not used.
	 * @param realisations the vector of realizations.
	 */
	public RandomVariableCuda(double time, final float[] realisations) {
		this(time, createCUdeviceptr(realisations), realisations.length);
		deviceMemoryPool.manage(this.realizations, this);
	}

	/**
	 * Create a stochastic random variable.
	 *
	 * @param time the filtration time, set to 0.0 if not used.
	 * @param realisations the vector of realizations.
	 */
	public RandomVariableCuda(double time, double[] realisations) {
		this(time, getFloatArray(realisations));
	}

	/**
	 * Create a stochastic random variable.
	 *
	 * @param realisations the vector of realizations.
	 */
	public RandomVariableCuda(float[] realisations) {
		this(0.0, realisations);
	}


	public static CUdeviceptr getCUdeviceptr(final long size) {
		return deviceMemoryPool.getCUdeviceptr(size);
	}

	public static void clean() {
		deviceMemoryPool.clean();
	}

	private static RandomVariableCuda getRandomVariableCuda(RandomVariable randomVariable) {
		if(randomVariable instanceof RandomVariableCuda) return (RandomVariableCuda)randomVariable;
		else {
			RandomVariableCuda randomVariableCuda = new RandomVariableCuda(randomVariable.getFiltrationTime(), randomVariable.getRealizations());
			return randomVariableCuda;
		}
	}

	/**
	 * @return
	 */
	private static float getDeviceFreeMemPercentage() {
		float freeRate;
		try {
			freeRate = deviceExecutor.submit(new Callable<Float>() { public Float call() {
				long[] free = new long[1];
				long[] total = new long[1];
				jcuda.runtime.JCuda.cudaMemGetInfo(free, total);
				float freeRate = ((float)free[0]/(total[0]));
				return freeRate;
			}}).get();
		} catch (InterruptedException | ExecutionException e) {
			return freeRate = Float.NaN;
		}
		return freeRate;
	}

	/**
	 * Create a vector on device and copy host vector to it.
	 *
	 * @param values Host vector.
	 * @return Pointer to device vector.
	 */
	private static CUdeviceptr createCUdeviceptr(final float[] values) {
		synchronized (deviceMemoryPool)
		{
			final CUdeviceptr cuDevicePtr = deviceMemoryPool.getCUdeviceptr(values.length);
			try {
				deviceExecutor.submit(new Runnable() { public void run() {
					cuCtxSynchronize();
					JCudaDriver.cuMemcpyHtoD(cuDevicePtr, Pointer.to(values), (long)values.length * Sizeof.FLOAT);
					cuCtxSynchronize();
				}}).get();
			} catch (InterruptedException | ExecutionException e) { throw new RuntimeException(e.getCause()); }
			return cuDevicePtr;
		}
	}

	private static float[] getFloatArray(double[] arrayOfDouble) {
		float[] arrayOfFloat = new float[arrayOfDouble.length];
		for(int i=0; i<arrayOfDouble.length; i++) {
			arrayOfFloat[i] = (float)arrayOfDouble[i];
		}
		return arrayOfFloat;
	}

	private static double[] getDoubleArray(float[] arrayOfFloat) {
		double[] arrayOfDouble = new double[arrayOfFloat.length];
		for(int i=0; i<arrayOfFloat.length; i++) {
			arrayOfDouble[i] = arrayOfFloat[i];
		}
		return arrayOfDouble;
	}

	@Override
	public boolean equals(RandomVariable randomVariable) {
		throw new UnsupportedOperationException();
		/*
		if(this.time != randomVariable.getFiltrationTime()) return false;
		if(this.isDeterministic() && randomVariable.isDeterministic()) {
			return this.valueIfNonStochastic == randomVariable.doubleValue();
		}

		if(this.isDeterministic() != randomVariable.isDeterministic()) return false;

		for(int i=0; i<realizations.length; i++) if(realizations[i] != randomVariable.get(i)) return false;

		return true;
		 */
	}

	@Override
	public double getFiltrationTime() {
		return time;
	}

	@Override
	public int getTypePriority() {
		return typePriority;
	}

	@Override
	public double get(int pathOrState) {
		if(isDeterministic())   return valueIfNonStochastic;
		else               		throw new UnsupportedOperationException();
	}

	@Override
	public int size() {
		if(isDeterministic())    return 1;
		else                     return (int)this.size;
	}

	@Override
	public double getMin() {
		throw new UnsupportedOperationException();
		/*
		if(isDeterministic()) return valueIfNonStochastic;
		double min = Double.MAX_VALUE;
		if(realizations.length != 0) min = realizations[0];     /// @see getMax()
		for(int i=0; i<realizations.length; i++) min = Math.min(realizations[i],min);
		return min;
		 */
	}

	@Override
	public double getMax() {
		throw new UnsupportedOperationException();
		/*
		if(isDeterministic()) return valueIfNonStochastic;
		double max = -Double.MAX_VALUE;
		if(realizations.length != 0) max = realizations[0];
		for(int i=0; i<realizations.length; i++) max = Math.max(realizations[i],max);
		return max;
		 */
	}

	@Override
	public double getAverage() {
		if(isDeterministic())	return valueIfNonStochastic;
		if(size() == 0)			return Double.NaN;

		// TODO: Use kernel
		float[] realizationsOnHostMemory = new float[(int)size];
		try {
			deviceExecutor.submit(new Runnable() { public void run() {
				cuCtxSynchronize();
				cuMemcpyDtoH(Pointer.to(realizationsOnHostMemory), realizations, size * Sizeof.FLOAT);
				cuCtxSynchronize();
			}}).get();
		} catch (InterruptedException | ExecutionException e) { throw new RuntimeException(e.getCause()); }

		return (new RandomVariableFromFloatArray(getFiltrationTime(), realizationsOnHostMemory)).getAverage();
		//return  reduce()/size();
	}

	@Override
	public double getAverage(RandomVariable probabilities) {
		return this.mult(probabilities).getAverage();
	}

	@Override
	public double getVariance() {
		if(isDeterministic())	return 0.0;
		if(size() == 0)			return Double.NaN;

		double average = getAverage();
		return this.squared().getAverage() - average*average;
	}

	@Override
	public double getVariance(RandomVariable probabilities) {
		double average = getAverage(probabilities);
		return this.squared().sub(average*average).getAverage(probabilities);
	}

	@Override
	public double getSampleVariance() {
		if(isDeterministic() || size() == 1)	return 0.0;
		if(size() == 0)							return Double.NaN;

		return getVariance() * size()/(size()-1);
	}

	@Override
	public double getStandardDeviation() {
		if(isDeterministic())	return 0.0;
		if(size() == 0)			return Double.NaN;

		return Math.sqrt(getVariance());
	}

	@Override
	public double getStandardDeviation(RandomVariable probabilities) {
		if(isDeterministic())	return 0.0;
		if(size() == 0)			return Double.NaN;

		return Math.sqrt(getVariance(probabilities));
	}

	@Override
	public double getStandardError() {
		if(isDeterministic())	return 0.0;
		if(size() == 0)			return Double.NaN;

		return getStandardDeviation()/Math.sqrt(size());
	}

	@Override
	public double getStandardError(RandomVariable probabilities) {
		if(isDeterministic())	return 0.0;
		if(size() == 0)			return Double.NaN;

		return getStandardDeviation(probabilities)/Math.sqrt(size());
	}

	@Override
	public double getQuantile(double quantile) {
		if(isDeterministic())	return valueIfNonStochastic;
		if(size() == 0)			return Double.NaN;

		throw new UnsupportedOperationException();
		/*
		float[] realizationsSorted = realizations.clone();
		java.util.Arrays.sort(realizationsSorted);

		int indexOfQuantileValue = Math.min(Math.max((int)Math.round((size()+1) * (1-quantile) - 1), 0), size()-1);

		return realizationsSorted[indexOfQuantileValue];
		 */
	}

	@Override
	public double getQuantile(double quantile, RandomVariable probabilities) {
		if(isDeterministic())	return valueIfNonStochastic;
		if(size() == 0)			return Double.NaN;

		throw new RuntimeException("Method not implemented.");
	}

	@Override
	public double getQuantileExpectation(double quantileStart, double quantileEnd) {
		if(isDeterministic())	return valueIfNonStochastic;
		if(size() == 0)			return Double.NaN;
		if(quantileStart > quantileEnd) return getQuantileExpectation(quantileEnd, quantileStart);

		throw new UnsupportedOperationException();
		/*
		float[] realizationsSorted = realizations.clone();
		java.util.Arrays.sort(realizationsSorted);

		int indexOfQuantileValueStart	= Math.min(Math.max((int)Math.round((size()+1) * quantileStart - 1), 0), size()-1);
		int indexOfQuantileValueEnd		= Math.min(Math.max((int)Math.round((size()+1) * quantileEnd - 1), 0), size()-1);

		double quantileExpectation = 0.0;
		for (int i=indexOfQuantileValueStart; i<=indexOfQuantileValueEnd;i++) {
			quantileExpectation += realizationsSorted[i];
		}
		quantileExpectation /= indexOfQuantileValueEnd-indexOfQuantileValueStart+1;

		return quantileExpectation;
		 */
	}

	@Override
	public double[] getHistogram(double[] intervalPoints)
	{
		throw new UnsupportedOperationException();
		/*
		double[] histogramValues = new double[intervalPoints.length+1];

		if(isDeterministic()) {
			java.util.Arrays.fill(histogramValues, 0.0);
			for (int intervalIndex=0; intervalIndex<intervalPoints.length; intervalIndex++)
			{
				if(valueIfNonStochastic > intervalPoints[intervalIndex]) {
					histogramValues[intervalIndex] = 1.0;
					break;
				}
			}
			histogramValues[intervalPoints.length] = 1.0;
		}
		else {
			float[] realizationsSorted = realizations.clone();
			java.util.Arrays.sort(realizationsSorted);

			int sampleIndex=0;
			for (int intervalIndex=0; intervalIndex<intervalPoints.length; intervalIndex++)
			{
				int sampleCount = 0;
				while (sampleIndex < realizationsSorted.length &&
						realizationsSorted[sampleIndex] <= intervalPoints[intervalIndex])
				{
					sampleIndex++;
					sampleCount++;
				}
				histogramValues[intervalIndex] = sampleCount;
			}
			histogramValues[intervalPoints.length] = realizationsSorted.length-sampleIndex;

			// Normalize histogramValues
			if(realizationsSorted.length > 0) {
				for(int i=0; i<histogramValues.length; i++) histogramValues[i] /= realizationsSorted.length;
			}
		}

		return histogramValues;
		 */
	}

	@Override
	public double[][] getHistogram(int numberOfPoints, double standardDeviations) {
		throw new UnsupportedOperationException();
		/*
		double[] intervalPoints = new double[numberOfPoints];
		double[] anchorPoints	= new double[numberOfPoints+1];
		double center	= getAverage();
		double radius	= standardDeviations * getStandardDeviation();
		double stepSize	= (double) (numberOfPoints-1) / 2.0;
		for(int i=0; i<numberOfPoints;i++) {
			double alpha = (-(double)(numberOfPoints-1) / 2.0 + (double)i) / stepSize;
			intervalPoints[i]	= center + alpha * radius;
			anchorPoints[i]		= center + alpha * radius - radius / (2 * stepSize);
		}
		anchorPoints[numberOfPoints] = center + 1 * radius + radius / (2 * stepSize);

		double[][] result = new double[2][];
		result[0] = anchorPoints;
		result[1] = getHistogram(intervalPoints);

		return result;
		 */
	}

	@Override
	public boolean isDeterministic() {
		return realizations == null;
	}

	@Override
	public RandomVariable cache() {
		return this;
		/*
		final float[] values = new float[(int)size];
		try {
			deviceExecutor.submit(new Runnable() { public void run() {
				cuCtxSynchronize();
				cuMemcpyDtoH(Pointer.to(values), realizations, size * Sizeof.FLOAT);
				cuCtxSynchronize();
			}}).get();
		} catch (InterruptedException | ExecutionException e) { throw new RuntimeException(e.getCause()); }
		return new RandomVariableFromFloatArray(time, values);
		 */
	}

	@Override
	public double[] getRealizations() {
		if(isDeterministic()) {
			double[] result = new double[] { valueIfNonStochastic };
			return result;
		}
		else {
			final float[] result = new float[(int)size];
			try {
				deviceExecutor.submit(new Runnable() { public void run() {
					cuCtxSynchronize();
					cuMemcpyDtoH(Pointer.to(result), realizations, size * Sizeof.FLOAT);
					cuCtxSynchronize();
				}}).get();
			} catch (InterruptedException | ExecutionException e) {
				throw new RuntimeException(e.getCause());
			}
			return getDoubleArray(result);
		}
	}

	@Override
	public Double doubleValue() {
		if(isDeterministic()) {
			return valueIfNonStochastic;
		} else {
			throw new UnsupportedOperationException("The random variable is non-deterministic");
		}
	}

	@Override
	public IntToDoubleFunction getOperator() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DoubleStream getRealizationsStream() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public RandomVariable apply(DoubleUnaryOperator function) {
		throw new UnsupportedOperationException();
		/*
		if(isDeterministic()) {
			double newValueIfNonStochastic = function.value(valueIfNonStochastic);
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			double[] newRealizations = new double[realizations.length];
			for(int i=0; i<newRealizations.length; i++) newRealizations[i]		 = function.value(realizations[i]);
			return RandomVariableCuda.of(time, newRealizations);
		}
		 */
	}

	@Override
	public RandomVariable apply(DoubleBinaryOperator operator, RandomVariable argument) {
		throw new UnsupportedOperationException();
	}

	@Override
	public RandomVariable apply(DoubleTernaryOperator operator, RandomVariable argument1, RandomVariable argument2) {
		throw new UnsupportedOperationException();
	}

	public RandomVariable cap(double cap) {
		if(isDeterministic()) {
			double newValueIfNonStochastic = Math.min(valueIfNonStochastic,cap);
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			// Allocate device output memory
			final CUdeviceptr result = 
					callCudaFunction(capByScalar, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(new float[] { (float)cap }),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	@Override
	public RandomVariable floor(double floor) {
		if(isDeterministic()) {
			double newValueIfNonStochastic = Math.max(valueIfNonStochastic,floor);
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(floorByScalar, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(new float[] { (float)floor }),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	@Override
	public RandomVariable add(double value) {
		if(isDeterministic()) {
			double newValueIfNonStochastic = valueIfNonStochastic + value;
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(addScalar, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(new float[] { (float)value }),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	@Override
	public RandomVariable sub(double value) {
		if(isDeterministic()) {
			double newValueIfNonStochastic = valueIfNonStochastic - value;
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(subScalar, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(new float[] { (float)value }),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	public RandomVariable bus(double value) {
		if(isDeterministic()) {
			double newValueIfNonStochastic = -valueIfNonStochastic + value;
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(busScalar, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(new float[] { (float)value }),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	@Override
	public RandomVariable mult(double value) {
		if(isDeterministic()) {
			double newValueIfNonStochastic = valueIfNonStochastic * value;
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(multScalar, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(new float[] { ((float)value) }),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	@Override
	public RandomVariable div(double value) {
		if(isDeterministic()) {
			double newValueIfNonStochastic = valueIfNonStochastic / value;
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(divScalar, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(new float[] { (float)value }),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	public RandomVariable vid(double value) {
		if(isDeterministic()) {
			double newValueIfNonStochastic = value / valueIfNonStochastic;
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(vidScalar, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(new float[] { (float)value }),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	@Override
	public RandomVariable pow(double exponent) {
		if(isDeterministic()) {
			double newValueIfNonStochastic = Math.pow(valueIfNonStochastic,exponent);
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(cuPow, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(new float[] { (float)exponent }),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	@Override
	public RandomVariable average() {
		return new RandomVariableCuda(getAverage());
	}

	@Override
	public RandomVariable squared() {
		if(isDeterministic()) {
			double newValueIfNonStochastic = valueIfNonStochastic * valueIfNonStochastic;
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		} else
			return this.mult(this);
	}

	@Override
	public RandomVariable sqrt() {
		if(isDeterministic()) {
			double newValueIfNonStochastic = Math.sqrt(valueIfNonStochastic);
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(cuSqrt, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	public RandomVariable invert() {
		if(isDeterministic()) {
			double newValueIfNonStochastic = 1.0/valueIfNonStochastic;
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(invert, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	public RandomVariable abs() {
		if(isDeterministic()) {
			double newValueIfNonStochastic = Math.abs(valueIfNonStochastic);
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(cuAbs, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	public RandomVariableCuda exp() {
		if(isDeterministic()) {
			double newValueIfNonStochastic = Math.exp(valueIfNonStochastic);
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(cuExp, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	public RandomVariableCuda log() {
		if(isDeterministic()) {
			double newValueIfNonStochastic = Math.log(valueIfNonStochastic);
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(cuLog, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	public RandomVariable sin() {
		throw new UnsupportedOperationException();
		/*
		if(isDeterministic()) {
			double newValueIfNonStochastic = Math.sin(valueIfNonStochastic);
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			double[] newRealizations = new double[realizations.length];
			for(int i=0; i<newRealizations.length; i++) newRealizations[i]		 = Math.sin(realizations[i]);
			return RandomVariableCuda.of(time, newRealizations);
		}
		 */
	}

	public RandomVariable cos() {
		throw new UnsupportedOperationException();
		/*
		if(isDeterministic()) {
			double newValueIfNonStochastic = Math.cos(valueIfNonStochastic);
			return new RandomVariableCuda(time, newValueIfNonStochastic);
		}
		else {
			double[] newRealizations = new double[realizations.length];
			for(int i=0; i<newRealizations.length; i++) newRealizations[i]		 = Math.cos(realizations[i]);
			return RandomVariableCuda.of(time, newRealizations);
		}
		 */
	}

	/*
	 * Binary operators: checking for return type priority.
	 */

	@Override
	public RandomVariable add(RandomVariable randomVariable) {
		if(randomVariable.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return randomVariable.add(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(time, randomVariable.getFiltrationTime());

		if(isDeterministic() && randomVariable.isDeterministic()) {
			double newValueIfNonStochastic = valueIfNonStochastic + randomVariable.doubleValue();
			return new RandomVariableCuda(newTime, newValueIfNonStochastic);
		}
		else if(isDeterministic()) {
			return getRandomVariableCuda(randomVariable).add(valueIfNonStochastic);
		}
		else if(randomVariable.isDeterministic()) {
			return this.add(randomVariable.doubleValue());
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(add, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(getRandomVariableCuda(randomVariable).realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	@Override
	public RandomVariable sub(RandomVariable randomVariable) {
		if(randomVariable.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return randomVariable.bus(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(time, randomVariable.getFiltrationTime());

		if(isDeterministic() && randomVariable.isDeterministic()) {
			double newValueIfNonStochastic = valueIfNonStochastic - randomVariable.doubleValue();
			return new RandomVariableCuda(newTime, newValueIfNonStochastic);
		}
		else if(isDeterministic()) {
			return getRandomVariableCuda(randomVariable).bus(valueIfNonStochastic);
		}
		else if(randomVariable.isDeterministic()) {
			return this.sub(randomVariable.doubleValue());
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(sub, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(getRandomVariableCuda(randomVariable).realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	@Override
	public RandomVariable bus(RandomVariable randomVariable) {
		if(randomVariable.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return randomVariable.sub(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(time, randomVariable.getFiltrationTime());

		if(isDeterministic() && randomVariable.isDeterministic()) {
			double newValueIfNonStochastic = -valueIfNonStochastic + randomVariable.doubleValue();
			return new RandomVariableCuda(newTime, newValueIfNonStochastic);
		}
		else if(isDeterministic()) {
			return getRandomVariableCuda(randomVariable).sub(valueIfNonStochastic);
		}
		else if(randomVariable.isDeterministic()) {
			return this.bus(randomVariable.doubleValue());
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(sub, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(getRandomVariableCuda(randomVariable).realizations),		// flipped arguments
							Pointer.to(realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(time, result, size());
		}
	}

	@Override
	public RandomVariable mult(RandomVariable randomVariable) {
		if(randomVariable.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return randomVariable.mult(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(time, randomVariable.getFiltrationTime());

		if(isDeterministic() && randomVariable.isDeterministic()) {
			double newValueIfNonStochastic = valueIfNonStochastic * randomVariable.doubleValue();
			return new RandomVariableCuda(newTime, newValueIfNonStochastic);
		}
		else if(randomVariable.isDeterministic()) {
			return this.mult(randomVariable.doubleValue());
		}
		else if(isDeterministic() && !randomVariable.isDeterministic()) {
			return getRandomVariableCuda(randomVariable).mult(this.valueIfNonStochastic);
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(mult, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(getRandomVariableCuda(randomVariable).realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(newTime, result, size());
		}
	}

	@Override
	public RandomVariable div(RandomVariable randomVariable) {
		if(randomVariable.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return randomVariable.vid(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(time, randomVariable.getFiltrationTime());

		if(isDeterministic() && randomVariable.isDeterministic()) {
			double newValueIfNonStochastic = valueIfNonStochastic / randomVariable.doubleValue();
			return new RandomVariableCuda(newTime, newValueIfNonStochastic);
		}
		else if(isDeterministic()) {
			return getRandomVariableCuda(randomVariable).vid(valueIfNonStochastic);
		}
		else if(randomVariable.isDeterministic()) {
			return this.div(randomVariable.doubleValue());
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(cuDiv, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(getRandomVariableCuda(randomVariable).realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(newTime, result, size());
		}
	}

	@Override
	public RandomVariable vid(RandomVariable randomVariable) {
		if(randomVariable.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return randomVariable.vid(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(time, randomVariable.getFiltrationTime());

		if(isDeterministic() && randomVariable.isDeterministic()) {
			double newValueIfNonStochastic = randomVariable.doubleValue() / valueIfNonStochastic;
			return new RandomVariableCuda(newTime, newValueIfNonStochastic);
		}
		else if(isDeterministic()) {
			return getRandomVariableCuda(randomVariable).div(valueIfNonStochastic);
		}
		else if(randomVariable.isDeterministic()) {
			return this.vid(randomVariable.doubleValue());
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(cuDiv, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(getRandomVariableCuda(randomVariable).realizations),
							Pointer.to(realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(newTime, result, size());
		}
	}

	@Override
	public RandomVariable cap(RandomVariable randomVariable) {
		if(randomVariable.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return randomVariable.cap(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(time, randomVariable.getFiltrationTime());

		if(isDeterministic() && randomVariable.isDeterministic()) {
			double newValueIfNonStochastic = Math.min(valueIfNonStochastic, randomVariable.doubleValue());
			return new RandomVariableCuda(newTime, newValueIfNonStochastic);
		}
		else if(isDeterministic()) return randomVariable.cap(valueIfNonStochastic);
		else {
			final CUdeviceptr result = 
					callCudaFunction(cap, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(((RandomVariableCuda)randomVariable).realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(newTime, result, size());
		}
	}

	@Override
	public RandomVariable floor(RandomVariable randomVariable) {
		if(randomVariable.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return randomVariable.floor(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(time, randomVariable.getFiltrationTime());

		if(isDeterministic() && randomVariable.isDeterministic()) {
			double newValueIfNonStochastic = Math.max(valueIfNonStochastic, randomVariable.doubleValue());
			return new RandomVariableCuda(newTime, newValueIfNonStochastic);
		}
		else if(isDeterministic()) {
			return getRandomVariableCuda(randomVariable).floor(valueIfNonStochastic);
		}
		else if(randomVariable.isDeterministic()) {
			return this.floor(randomVariable.doubleValue());
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(cuFloor, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(((RandomVariableCuda)randomVariable).realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(newTime, result, size());
		}
	}

	@Override
	public RandomVariable accrue(RandomVariable rate, double periodLength) {
		if(rate.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return rate.mult(periodLength).add(1.0).mult(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(time, rate.getFiltrationTime());

		if(rate.isDeterministic()) {
			return this.mult(1.0 + rate.doubleValue() * periodLength);
		}
		else if(isDeterministic() && !rate.isDeterministic())
			return getRandomVariableCuda(rate.mult(periodLength).add(1.0).mult(valueIfNonStochastic));
		else {
			final CUdeviceptr result = 
					callCudaFunction(accrue, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(((RandomVariableCuda)rate).realizations),
							Pointer.to(new float[] { (float)periodLength }),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(newTime, result, size());
		}
	}

	@Override
	public RandomVariable discount(RandomVariable rate, double periodLength) {
		if(rate.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return rate.mult(periodLength).add(1.0).invert().mult(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(time, rate.getFiltrationTime());

		if(rate.isDeterministic()) {
			return this.div(1.0 + rate.doubleValue() * periodLength);
		}
		else if(isDeterministic() && !rate.isDeterministic()) {
			if(valueIfNonStochastic == 0) return this;
			return ((RandomVariableCuda)getRandomVariableCuda(rate.mult(periodLength).add(1.0)).vid(valueIfNonStochastic));
		}
		else {
			final CUdeviceptr result = 
					callCudaFunction(discount, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(((RandomVariableCuda)rate).realizations),
							Pointer.to(new float[] { (float)periodLength }),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(newTime, result, size());
		}
	}

	/*
	 * Ternary operators: checking for return type priority.
	 * @TODO add checking for return type priority.
	 */

	@Override
	public RandomVariable choose(RandomVariable valueIfTriggerNonNegative, RandomVariable valueIfTriggerNegative) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public RandomVariable addProduct(RandomVariable factor1, double factor2) {
		if(factor1.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return factor1.mult(factor2).add(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(time, factor1.getFiltrationTime());

		if(factor1.isDeterministic()) {
			return this.add(factor1.doubleValue() * factor2);
		}
		else if(!isDeterministic() && !factor1.isDeterministic()) {
			final CUdeviceptr result = 
					callCudaFunction(addProduct_vs, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(((RandomVariableCuda)factor1).realizations),
							Pointer.to(new float[] { (float)factor2 }),
							null /* placeholder for result */ }
							);
			return RandomVariableCuda.of(newTime, result, size());
		} else
			return this.add(factor1.mult(factor2));
	}

	@Override
	public RandomVariable addProduct(RandomVariable factor1, RandomVariable factor2) {
		if(factor1.getTypePriority() > this.getTypePriority() || factor2.getTypePriority() > this.getTypePriority()) {
			// Check type priority
			return factor1.mult(factor2).add(this);
		}

		// Set time of this random variable to maximum of time with respect to which measurability is known.
		double newTime = Math.max(Math.max(time, factor1.getFiltrationTime()), factor2.getFiltrationTime());

		if(isDeterministic() && factor1.isDeterministic() && factor2.isDeterministic()) {
			double newValueIfNonStochastic = valueIfNonStochastic + (factor1.doubleValue() * factor2.doubleValue());
			return new RandomVariableCuda(newTime, newValueIfNonStochastic);
		}
		else if(factor1.isDeterministic() && factor2.isDeterministic()) {
			return add(factor1.doubleValue() * factor2.doubleValue());
		}
		else if(factor2.isDeterministic()) {
			return this.addProduct(factor1, factor2.doubleValue());
		}
		else if(factor1.isDeterministic()) {
			return this.addProduct(factor2, factor1.doubleValue());
		}
		else if(!isDeterministic() && !factor1.isDeterministic() && !factor2.isDeterministic()) {
			final CUdeviceptr result = 
					callCudaFunction(addProduct, new Pointer[] {
							Pointer.to(new int[] { size() }),
							Pointer.to(realizations),
							Pointer.to(((RandomVariableCuda)factor1).realizations),
							Pointer.to(((RandomVariableCuda)factor2).realizations),
							null /* placeholder for result */ }
							);

			return RandomVariableCuda.of(newTime, result, size());
		} else
			return this.add(factor1.mult(factor2));
	}

	@Override
	public RandomVariable addRatio(RandomVariable numerator, RandomVariable denominator) {
		// TODO Implement a kernel here
		return this.add(numerator.div(denominator));
	}

	@Override
	public RandomVariable subRatio(RandomVariable numerator, RandomVariable denominator) {
		// TODO Implement a kernel here
		return this.sub(numerator.div(denominator));
	}

	/* (non-Javadoc)
	 * @see net.finmath.stochastic.RandomVariable#isNaN()
	 */
	@Override
	public RandomVariable isNaN() {
		// TODO Auto-generated method stub
		return null;
	}

	/*
	 * Cuda specific implementations
	 */

	private double reduce() {
		if(this.isDeterministic()) return valueIfNonStochastic;

		RandomVariableCuda reduced = this;
		while(reduced.size() > 1) {
			reduced = reduced.reduceBySize(reduceGridSize);
		}
		return reduced.getRealizations()[0];
	}

	private RandomVariableCuda reduceBySize(int bySize) {
		synchronized (deviceMemoryPool) {

			int blockSizeX = bySize;
			int gridSizeX = (int)Math.ceil((double)size()/2 / blockSizeX);
			final CUdeviceptr reduceVector = getCUdeviceptr(gridSizeX);

			callCudaFunction(reducePartial, new Pointer[] {
					Pointer.to(new int[] { size() }),
					Pointer.to(realizations),
					Pointer.to(reduceVector)},
					gridSizeX, blockSizeX, blockSizeX);

			return RandomVariableCuda.of(-Double.MAX_VALUE, reduceVector, gridSizeX);
		}
	}

	private CUdeviceptr callCudaFunction(CUfunction function, Pointer[] arguments) {
		synchronized (deviceMemoryPool) {
			CUdeviceptr result = getCUdeviceptr(size());
			arguments[arguments.length-1] = Pointer.to(result);

			int blockSizeX = 256;
			int gridSizeX = (int)Math.ceil((double)size() / blockSizeX);
			callCudaFunction(function, arguments, gridSizeX, blockSizeX, 0);
			return result;
		}
	}

	private void callCudaFunction(final CUfunction function, Pointer[] arguments, final int gridSizeX, final int blockSizeX, final int sharedMemorySize) {
		synchronized (deviceMemoryPool) {
			// Set up the kernel parameters: A pointer to an array
			// of pointers which point to the actual values.
			final Pointer kernelParameters = Pointer.to(arguments);

			try {
				deviceExecutor.submit(new Runnable() { public void run() {
					cuCtxSynchronize();
					cuLaunchKernel(function,
							gridSizeX,  1, 1,      // Grid dimension
							blockSizeX, 1, 1,      // Block dimension
							sharedMemorySize * Sizeof.FLOAT, null,               // Shared memory size and stream
							kernelParameters, null // Kernel- and extra parameters
							);
				}}).get();
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
				throw new RuntimeException("Cuda calculation of " + function + " failed.");
			}
		}
	}
}