import { Form, Formik } from 'formik'
import * as Yup from 'yup'
import { FileField, FormField } from './FormField'
import type { CriarPostagemPayload } from '../../types/api'

const validationSchema = Yup.object({
    legenda: Yup.string().defined(),
    imagem: Yup.mixed<File>().nullable().defined(),
})

interface FormCriarPostagemProps {
    onSubmit: (values: CriarPostagemPayload) => Promise<void>
}

function FormCriarPostagem({ onSubmit }: FormCriarPostagemProps) {
    const initialValues: CriarPostagemPayload = { legenda: '', imagem: null }

    return (
        <Formik initialValues={initialValues} validationSchema={validationSchema} onSubmit={onSubmit}>
            {({ isSubmitting }) => (
                <Form noValidate>
                    <FileField name="imagem" label="Imagem da postagem" />
                    <FormField name="legenda" label="Legenda" as="textarea" height={150} />
                    <button className="btn btn-primary" type="submit" disabled={isSubmitting}>
                        Postar
                    </button>
                </Form>
            )}
        </Formik>
    )
}

export default FormCriarPostagem
