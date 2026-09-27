package com.naqqa.elasticsearch.search.aggs.metrics;

public final class MatrixStatsState {

    final String[] fields;
    long count;
    final double[] means;
    final double[][] comoments;

    public MatrixStatsState(String[] fields) {
        this.fields = fields;
        this.means = new double[fields.length];
        this.comoments = new double[fields.length][fields.length];
    }

    public void add(double[] values) {
        count++;
        double[] delta = new double[fields.length];
        for (int i = 0; i < fields.length; i++) {
            delta[i] = values[i] - means[i];
            means[i] += delta[i] / count;
        }
        for (int i = 0; i < fields.length; i++) {
            for (int j = 0; j < fields.length; j++) {
                comoments[i][j] += delta[i] * (values[j] - means[j]);
            }
        }
    }

    public MatrixStatsState merge(MatrixStatsState other) {
        MatrixStatsState result = new MatrixStatsState(fields);
        long n = count + other.count;
        if (n == 0) {
            return result;
        }
        double[] delta = new double[fields.length];
        for (int i = 0; i < fields.length; i++) {
            delta[i] = other.means[i] - means[i];
            result.means[i] = count == 0 ? other.means[i] : means[i] + (other.count == 0 ? 0 : delta[i] * other.count / n);
        }
        for (int i = 0; i < fields.length; i++) {
            for (int j = 0; j < fields.length; j++) {
                double cross = (count == 0 || other.count == 0) ? 0 : delta[i] * delta[j] * count * other.count / n;
                result.comoments[i][j] = comoments[i][j] + other.comoments[i][j] + cross;
            }
        }
        result.count = n;
        return result;
    }

    public double variance(int i) {
        return count < 2 ? Double.NaN : comoments[i][i] / (count - 1);
    }

    public double covariance(int i, int j) {
        return count < 2 ? Double.NaN : comoments[i][j] / (count - 1);
    }

    public double correlation(int i, int j) {
        double vi = variance(i);
        double vj = variance(j);
        if (vi <= 0 || vj <= 0 || Double.isNaN(vi) || Double.isNaN(vj)) {
            return Double.NaN;
        }
        return covariance(i, j) / Math.sqrt(vi * vj);
    }
}
