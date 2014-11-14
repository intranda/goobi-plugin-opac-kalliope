package de.intranda.utils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SimpleMatrix<T> {

    private final T[][] matrix;
    private final int columns;
    private final int rows;

    public SimpleMatrix(int rows, int columns) {
        this.columns = columns;
        this.rows = rows;
        matrix = (T[][]) new Object[rows][columns];
    }

    public SimpleMatrix(final List<List<T>> list) {
        this.rows = list.size();
        this.columns = getMaxSublistSize(list);
        matrix = (T[][]) new Object[rows][columns];
        MatrixFiller<T> filler = new MatrixFiller<T>() {
            
            @Override
            public T calculateValue(int row, int column) {
                List<T> sublist = list.get(row);
                if(column < sublist.size()) {                    
                    return sublist.get(column);
                } else {
                    return null;
                }
            }
        };
        fill(filler);
    }

    private int getMaxSublistSize(List<List<T>> list) {
        int maxSize = 0;
        for (List<T> sublist : list) {
            maxSize = Math.max(maxSize, sublist.size());
        }
        return maxSize;
    }
    
    public List<T> getRowAsList(int rowIndex) {
        T[] row = matrix[rowIndex];
        List<T> list = new ArrayList<T>(rows);
        for (T value : row) {
            if(value != null) {
                list.add(value);
            }
        }
        return list;//Arrays.asList(row);
    }
    
    public List<T> getColumnAsList(int columnIndex) {
//        T[] column = (T[]) new Object[rows];
        List<T> list = new ArrayList<T>(rows);
        for (int i = 0; i < rows; i++) {
            if(matrix[i][columnIndex] != null) {                
                list.add(matrix[i][columnIndex]);
            }
        }
        return list;//Arrays.asList(column);
    }

    public void set(T value, int row, int column) {
        matrix[row][column] = value;
    }

    public T get(int row, int column) {
        return matrix[row][column];
    }

    public int getColumns() {
        return columns;
    }

    public int getRows() {
        return rows;
    }
    
    public void fill(T value) {
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) {
                set(value, i, j);
            }
        }
    }
    
    public void fill(MatrixFiller<T> filler) {
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) {
                set(filler.calculateValue(i, j), i, j);
            }
        }
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) {
                sb.append(get(i, j));
                sb.append("\t");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
    
    public static interface MatrixFiller<T> {

        public T calculateValue(int row, int column);
    }

    public static void main(String[] args) {
        SimpleMatrix<String> matrix = new SimpleMatrix<String>(4, 3);
//        matrix.fill("-");
        System.out.println(matrix);
        System.out.println("-----------------------------------------");
        MatrixFiller filler = new MatrixFiller<String>() {
            
            @Override
            public String calculateValue(int row, int column) {
                return row + ", " + column;
            }
        };
        matrix.fill(filler);
        System.out.println(matrix);
        System.out.println("-----------------------------------------");
        matrix.set("bla, bla", 2, 1);
        System.out.println(matrix);
        System.out.println("-----------------------------------------");
        List<String> row2 = matrix.getRowAsList(2);
        List<String> col1 = matrix.getColumnAsList(1);
        System.out.println("row 2 = " + row2);
        System.out.println("column 1 = " + col1);
    }

}
