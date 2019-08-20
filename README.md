# finmath lib cuda extensions

- - - -
** Enabling finmath lib with cuda via jcuda. - Running finmath lib models on a GPU **
- - - -

The finmath lib cuda extensions provide an Cuda implementation of the finmath lib interfaces `RandomVariable` and `BrownianMotion` compatible with finmath lib 4.0.12 or later.

A `RandomVariableCudaFactory` is provided, which can be inject in any finmath lib model using a random variable factory to construct `RandomVariable` objects. Objects created from this factory or from objects created from this factory perform their calculation on the GPU.

The `RandomVariableCudaFactory` can be combined with AAD wrappers, to allow algorithmic differentiation together with calculations performed on the GPU.

In addition, objects of type `BrownianMotion` are also taking the role of a factory for objects of type `RandomVariable`. Thus, injecting the `BrownianMotionCuda` into classes consuming a `BrownianMotion` will result in finmath-lib models performing their calculations on the GPU - seamlessly.


## Example

Create a vector of floats on the GPU device
```
RandomVariableInterface randomVariable = new RandomVariableCuda(new float[] {-4.0f, -2.0f, 0.0f, 2.0f, 4.0f} );
```
perform some calculations (still on the GPU device)
```
randomVariable = randomVariable.add(4.0);
randomVariable = randomVariable.div(2.0);
```
perform a reduction on the GPU device
```
double average = randomVariable.getAverage();
```
or get the result vector (to the host)
```
double[] result = randomVariable.getRealizations();
```
(note: the result is always double, since different implementation may support float or double on the device).

## Installation

Of course, you should have NVidia Cuda 10.0 installed. (If you like to use a different version, you can try to switch the JCuda version in the Maven pom.xml).

To obtain and build the finmath-lib-cuda-extensions then do
```
git clone https://github.com/cfries/finmath-lib-cuda-extensions.git
cd finmath-lib-cuda-extensions
mvn clean package
```
If everything goes well, you will see unit test  run. Note that some of the tests may fail if the device (GPU) has not enough memory. 

## Trying on Amazon EC2

If you do not have a machine with NVidia Cuda 10.0 at hand, you may try out the finmath-lib-cuda-extensions on an Amazon EC2 machine. To do so:

* Create an Amazon AWS account (if needed) an go to your AWS console.
* Select to start an EC2 virtual server.
* Launch a GPU instance
  - Filter the list of images (AMI) using `gpu` and select - e.g. - `Deep Learning Base AMI (Ubuntu) Version 19.0`.
  - Filter the list of servers using the "GPU instances" and select an instance.
* Login to your GPU instance.
* Check that you have cuda 10.0 (e.g. use `nvcc --version`)
* Try finmath-lib-cuda-extensions as described in the previous section.

## Performance

### Unit test for random number generation:

```
Running net.finmath.montecarlo.BrownianMotionTest
Test of performance of BrownianMotionLazyInit                  	..........test took 49.057 sec.
Test of performance of BrownianMotionJavaRandom                	..........test took 65.558 sec.
Test of performance of BrownianMotionCudaWithHostRandomVariable	..........test took 4.633 sec.
Test of performance of BrownianMotionCudaWithRandomVariableCuda	..........test took 2.325 sec.
```

### Unit test for Monte-Carlo simulation

```
Running net.finmath.montecarlo.assetderivativevaluation.MonteCarloBlackScholesModelTest
BrownianMotionLazyInit
   value Monte-Carlo =  0.1898	 value analytic    =  0.1899	 calculation time =  4.00 sec.

BrownianMotionJavaRandom
   value Monte-Carlo =  0.1901	 value analytic    =  0.1899	 calculation time =  5.19 sec.

BrownianMotionCudaWithHostRandomVariable
   value Monte-Carlo =  0.1898	 value analytic    =  0.1899	 calculation time =  2.50 sec.

BrownianMotionCudaWithRandomVariableCuda
   value Monte-Carlo =  0.1898	 value analytic    =  0.1899	 calculation time =  0.09 sec.
```

Remark:
* `BrownianMotionLazyInit`: Calculation on CPU, using Mersenne Twister.
* `BrownianMotionJavaRandom`: Calculation on CPU, using Java random number generator (LCG).
* `BrownianMotionCudaWithHostRandomVariable`: Calculation on CPU and GPU: Random number generator on GPU, Simulation on CPU.
* `BrownianMotionCudaWithRandomVariableCuda`: Calculation on GPU: Random number generator on GPU, Simulation on GPU.

## References

* [finmath lib Project documentation](http://finmath.net/finmath-lib/)
provides the documentation of the library api.
* [finmath lib API documentation](http://finmath.net/finmath-lib/apidocs/)
provides the documentation of the library api.
* [finmath.net special topics](http://www.finmath.net/topics)
cover some selected topics with demo spreadsheets and uml diagrams.
Some topics come with additional documentations (technical papers).

## License

The code of "finmath lib", "finmath experiments" and "finmath lib cuda extensions" (packages
`net.finmath.*`) are distributed under the [Apache License version
2.0](http://www.apache.org/licenses/LICENSE-2.0.html), unless otherwise explicitly stated.