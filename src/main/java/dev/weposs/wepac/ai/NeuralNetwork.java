package dev.weposs.wepac.ai;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Tiny feed-forward neural network (tanh hidden layers, sigmoid output)
 * trained with plain backpropagation / SGD. Pure Java, no dependencies.
 * Not thread-safe by itself; AIBrain synchronises access.
 */
public final class NeuralNetwork {

    public static final int FORMAT_VERSION = 1;

    private final int[] sizes;
    private final double[][][] weights; // weights[l][i][j]: neuron i of layer l -> neuron j of layer l+1
    private final double[][] biases;    // biases[l][j]
    private final Random random = new Random(0xBEEFL);
    private double learningRate = 0.02;
    private long trainSteps = 0;

    public NeuralNetwork(int... sizes) {
        if (sizes.length < 2) {
            throw new IllegalArgumentException("need at least input and output layer");
        }
        this.sizes = sizes.clone();
        int layers = sizes.length - 1;
        this.weights = new double[layers][][];
        this.biases = new double[layers][];
        for (int l = 0; l < layers; l++) {
            int fanIn = sizes[l];
            int fanOut = sizes[l + 1];
            double bound = Math.sqrt(6.0 / (fanIn + fanOut));
            weights[l] = new double[fanIn][fanOut];
            biases[l] = new double[fanOut];
            for (int i = 0; i < fanIn; i++) {
                for (int j = 0; j < fanOut; j++) {
                    weights[l][i][j] = (random.nextDouble() * 2.0 - 1.0) * bound;
                }
            }
        }
    }

    public void setLearningRate(double lr) {
        this.learningRate = lr;
    }

    public long trainSteps() {
        return trainSteps;
    }

    /** Number of input features this network was built for. */
    public int inputSize() {
        return sizes[0];
    }

    /** Probability in [0,1] for the given (normalised) input. */
    public double predict(double[] input) {
        double[][] acts = forwardAll(input);
        return acts[acts.length - 1][0];
    }

    private double[][] forwardAll(double[] input) {
        int layers = weights.length;
        double[][] acts = new double[layers + 1][];
        acts[0] = input;
        for (int l = 0; l < layers; l++) {
            boolean output = (l == layers - 1);
            double[] prev = acts[l];
            double[] out = new double[sizes[l + 1]];
            for (int j = 0; j < out.length; j++) {
                double sum = biases[l][j];
                for (int i = 0; i < prev.length; i++) {
                    sum += prev[i] * weights[l][i][j];
                }
                out[j] = output ? sigmoid(sum) : Math.tanh(sum);
            }
            acts[l + 1] = out;
        }
        return acts;
    }

    /** One SGD step. Returns absolute error of this sample. */
    public double train(double[] input, double target) {
        int layers = weights.length;
        double[][] acts = forwardAll(input);
        double out = acts[layers][0];
        double err = out - target;

        double[][] deltas = new double[layers][];
        deltas[layers - 1] = new double[]{err * out * (1.0 - out)};
        for (int l = layers - 2; l >= 0; l--) {
            int n = sizes[l + 1];
            deltas[l] = new double[n];
            for (int j = 0; j < n; j++) {
                double sum = 0.0;
                for (int k = 0; k < deltas[l + 1].length; k++) {
                    sum += weights[l + 1][j][k] * deltas[l + 1][k];
                }
                double a = acts[l + 1][j];
                deltas[l][j] = sum * (1.0 - a * a);
            }
        }
        for (int l = 0; l < layers; l++) {
            double[] act = acts[l];
            double[] delta = deltas[l];
            for (int i = 0; i < act.length; i++) {
                double ai = act[i];
                if (ai == 0.0) continue;
                for (int j = 0; j < delta.length; j++) {
                    weights[l][i][j] -= learningRate * delta[j] * ai;
                }
            }
            for (int j = 0; j < delta.length; j++) {
                biases[l][j] -= learningRate * delta[j];
            }
        }
        trainSteps++;
        return Math.abs(err);
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    public void save(DataOutputStream out) throws IOException {
        out.writeInt(FORMAT_VERSION);
        out.writeDouble(learningRate);
        out.writeLong(trainSteps);
        out.writeInt(sizes.length);
        for (int s : sizes) {
            out.writeInt(s);
        }
        for (double[][] layer : weights) {
            for (double[] row : layer) {
                for (double v : row) {
                    out.writeDouble(v);
                }
            }
        }
        for (double[] layer : biases) {
            for (double v : layer) {
                out.writeDouble(v);
            }
        }
    }

    public static NeuralNetwork load(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("unsupported model version " + version);
        }
        double lr = in.readDouble();
        long steps = in.readLong();
        int dims = in.readInt();
        int[] sizes = new int[dims];
        for (int i = 0; i < dims; i++) {
            sizes[i] = in.readInt();
        }
        NeuralNetwork net = new NeuralNetwork(sizes);
        net.learningRate = lr;
        net.trainSteps = steps;
        for (int l = 0; l < net.weights.length; l++) {
            for (int i = 0; i < net.weights[l].length; i++) {
                for (int j = 0; j < net.weights[l][i].length; j++) {
                    net.weights[l][i][j] = in.readDouble();
                }
            }
        }
        for (int l = 0; l < net.biases.length; l++) {
            for (int j = 0; j < net.biases[l].length; j++) {
                net.biases[l][j] = in.readDouble();
            }
        }
        return net;
    }
}
